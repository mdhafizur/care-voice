# Indexing: design, evidence, guard rails

Every index in CareVoice exists for a named query. Three things back each one:

| | What | Where |
|---|---|---|
| **Design** | Which query each index serves and why its columns are in that order | this document |
| **Evidence** | Before/after numbers on 1M rows: the deployed index against dropped or differently shaped alternatives | `make db-bench` → `build/db-bench/report.md` |
| **Guard rail** | CI fails when a hot query stops using its index (query changed, index dropped, statistics lost) | `QueryPlanTest` (PostgreSQL), `AlertQueryPlanTest` (MongoDB) |

Related decisions: [ADR-0004](adr/0004-postgresql-for-documentation-service.md) (PostgreSQL),
[ADR-0005](adr/0005-mongodb-for-alert-service.md) (MongoDB),
[ADR-0011](adr/0011-keyset-pagination-and-optimistic-locking.md) (keyset pagination),
[ADR-0024](adr/0024-query-plans-are-tested.md) (why plans are tested).

## 1. Rules the indexes follow

1. **Equality columns first, then the sort columns in the query's order and direction.** An index on
   `(resident_id, recorded_at DESC, id DESC)` returns rows already in order, so `ORDER BY ... LIMIT 21` stops after
   21 index entries. With only `(resident_id)`, the database must read *all* of the resident's rows and sort them.
   MongoDB calls the same rule **ESR**: Equality, Sort, Range.
2. **Keyset, not OFFSET.** `WHERE (recorded_at, id) < (cursor)` is an index *condition*: the scan starts right at
   the cursor. `OFFSET 9999` reads and throws away 9,999 rows on every request. The `id` is in the index to break ties,
   because the vitals of one transcript share the same `recorded_at`.
3. **Partial indexes for small, busy subsets.** The review queue (1 % of observations) and the outbox backlog (a few
   hundred out of hundreds of thousands of rows) are indexed with a `WHERE` condition. Such an index stays tiny, however
   big the table grows.
4. **Uniqueness belongs to the database.** Unique indexes enforce idempotency (`transcripts_idempotency_uq`,
   `ux_alerts_event_rule`, `ux_processed_events_event`). Code that checks first and then inserts is not enough,
   because two pods can do the check at the same time.
5. **Every index has a write cost.** An insert into `observations` updates four or five indexes. Each extra index
   must earn its place with a query it serves (see [Write cost](#5-write-cost)).
6. **The planner decides by statistics.** The planner can ignore a correct index when its row estimate is wrong.
   So the statistics are part of the schema (see [finding 1](#finding-1-correlated-scope-columns-fixed-in-v3)).

## 2. Index catalogue

### PostgreSQL (documentation-service)

| Index | Serves | Shape and why |
|---|---|---|
| `observations_resident_recorded_idx` `(resident_id, recorded_at DESC, id DESC)` | `GET /v1/residents/{id}/observations` (`listByResident`), its count | Equality on the resident, then the keyset order: first page and every next page read exactly `limit + 1` entries |
| `observations_ward_recorded_idx` `(ward_id, recorded_at DESC, id DESC)` | `QUERY /v1/observations` (all filters), SSE replay (`findAfter`, read backward) | Every ward query is scoped to one ward; extra filters (vital type, resident set) are applied while walking the index in order |
| `observations_needs_review_idx` `(ward_id, recorded_at DESC) WHERE status = 'NEEDS_REVIEW'` | Review queue: `QUERY` with `status=NEEDS_REVIEW` | Partial: 0.4 MB instead of a 56 MB full index at 1M rows |
| `observations_transcript_idx` `(transcript_id)` | Foreign key to `transcripts` | Without it, deleting or re-keying a transcript would scan `observations` for referencing rows |
| `observations_values_ck` (CHECK, not an index) | Exactly the value columns of the vital type are set | Correctness in the database |
| `transcripts_idempotency_uq` UNIQUE `(created_by, idempotency_key)` | Idempotent `POST /v1/residents/{residentId}/transcripts` | The unique violation *is* the "already processed" signal; same key from another user is a different request |
| `transcripts_retention_idx` `(created_at) WHERE text_enc IS NOT NULL` | Retention job that nulls raw text after the retention period | Partial: only rows that still hold text; it shrinks as the job does its work |
| `transcripts_resident_idx`, `residents_ward_idx`, `wards_tenant_idx` | Foreign keys and the demo/seed lookups | Small tables; FK indexes avoid sequential scans on delete and join |
| `outbox_pending_idx` `(next_attempt_at) WHERE status = 'PENDING'` | Relay claim (`FOR UPDATE SKIP LOCKED`), backlog gauges | The outbox only grows; the partial index holds only the backlog (16 kB at 333k rows) |
| `audit_log_entity_idx`, `audit_log_occurred_idx` | Audit lookups by entity and by time | Append-only table |
| `observations_scope_deps` (`CREATE STATISTICS ... (dependencies)`, V3) | All observation queries | Tells the planner that tenant → ward → resident are dependent ([finding 1](#finding-1-correlated-scope-columns-fixed-in-v3)) |

### MongoDB (alert-service)

| Index | Serves | Shape and why |
|---|---|---|
| `ux_alerts_event_rule` UNIQUE `{eventId: 1, rule: 1}` | Idempotent alert creation (redelivered events) | A duplicate insert fails in the database; `insertMany(ordered=false)` keeps the new ones |
| `ix_alerts_scope_status_created` `{tenantId, wardId, status, createdAt: -1, _id: -1}` | `GET /v1/alerts?status=OPEN` (the ward lead's main screen), keyset pages | ESR: three equalities, then the sort; only matching documents are fetched |
| `ix_alerts_scope_created` `{tenantId, wardId, createdAt: -1, _id: -1}` | `GET /v1/alerts` without a status filter | Without it, the status index would have to merge every status branch to produce one order |
| `ux_processed_events_event` UNIQUE `{eventId: 1}` | Consumer idempotency (`processed_events`) | Duplicate deliveries are detected by the database, across pods |
| `ttl_processed_events_processed_at` TTL `{processedAt: 1}` | Expiry of idempotency records | MongoDB deletes them in the background; the collection does not grow forever |
| `ix_protocol_chunks_model_order` `{model: 1, order: 1}` | Loading the RAG corpus for the active embedding model | Exact lookup plus ordered read |

## 3. Measured: 1M observations

Reference run of `make db-bench` (PostgreSQL 16, 2 vCPU, shared_buffers 256 MB, data cached; median of six runs).
Your machine will give other absolute times; the ratios and the buffer counts are what matter, and buffers (8 kB pages
touched) do not depend on the machine. Shape: 2,500 residents in 50 wards of 5 tenants, one year, 20k observations per
ward, 400 per resident, 333k outbox rows of which 150 are pending.

| Query | Deployed | Alternative | Rows read: deployed vs alternative | Buffers | Time |
|---|---|---|--:|--:|--:|
| Resident history, first page | composite index + V3 statistics | single-column `(resident_id)` | 21 vs 402 | 11 vs 288 | 0.014 vs 1.18 ms |
| Resident history, first page | composite index + V3 statistics | no observation indexes | 21 vs 1,000,000 | 11 vs 21,830 | 0.014 vs 56 ms |
| Ward list, page 500 | keyset `(recorded_at, id) < cursor` | `OFFSET 9999` | 21 vs 20,010 | 8 vs 3,966 | 0.018 vs 12.8 ms |
| Review queue | partial index (0.4 MB) | ward index + filter (56 MB) | 22 vs 1,558 | 24 vs 320 | 0.021 vs 0.44 ms |
| Outbox claim (50 rows) | partial index (16 kB) | full `(status, next_attempt_at)` (13 MB) | 150 vs 150 | 708 vs 810 | 0.44 vs 0.55 ms |
| Outbox claim (50 rows) | partial index | no index | 150 vs 333,433 | 708 vs 9,688 | 0.44 vs 19 ms |
| Outbox gauges | partial index | no index | 150 vs 333,333 | 6 vs 9,037 | 0.03 vs 34 ms |
| SSE replay (50 events) | pkey + ward index backward | | 51 | 19 | 0.06 ms |

What the table shows:

- The **column order** is the difference between reading 21 and 402 rows per page, not the presence of an index on the
  resident. A single-column index is as slow as no ordered index at all.
- **OFFSET** costs grow with the page number; keyset costs stay flat (page 1 and page 500 both read 21 entries).
- The **partial outbox index** is as fast as a full one at 1/800 of its size, and it stays 16 kB however large the
  table gets. Without an index, the relay (which polls every 500 ms on every pod when it is idle) would read the whole
  table each time.

The MongoDB half of the report (open alerts with the deployed ESR index vs status-after-sort, vs no status in the
index, vs collection scan; a deep keyset page) is produced by the same `make db-bench` run. Its key numbers are
*keys examined* and *docs examined* compared with *returned*.

## 4. Findings

### Finding 1: correlated scope columns (fixed in V3)

The benchmark showed that the composite resident index was **not used for its order**. The plan combined the
resident and the ward index (`BitmapAnd`), read all 402 rows from the table and sorted them. The cause was the row
estimate: **1 row instead of 402**. Every query filters `tenant_id AND ward_id AND resident_id`, because tenant
isolation is enforced in SQL. The planner assumes the three columns are independent and multiplies their
selectivities (1/2500 × 1/50 × 1/5). In reality a resident determines the ward and the tenant. With an estimate of one
row, "fetch it and sort" looks free.

`V3__observation_scope_statistics.sql` adds dependency statistics:

```sql
CREATE STATISTICS observations_scope_deps (dependencies) ON tenant_id, ward_id, resident_id FROM observations;
```

The estimate becomes about 400. The plan becomes an ordered index scan that stops after 21 entries: **292 → 11
buffers, 1.2 ms → 0.014 ms**. This only changes metadata: no table rewrite, no index build, and its lock
(`SHARE UPDATE EXCLUSIVE`) blocks neither reads nor writes. `QueryPlanTest` fails if the statistics are ever
dropped: without them, the plan at test scale falls back to `BitmapAnd` + `Sort`.

### Finding 2: `X-Total-Count` is O(rows in scope)

Every list page also runs `count(*)` for its scope: 7.2 ms and 3,966 buffers for a ward of 20k observations, on
every page. The ward index does not contain `tenant_id`, so the table itself is read for the tenant check. An index
that starts with `(tenant_id, ward_id, ...)` allows an index-only scan (192 buffers, 3.4 ms), but costs another
74 MB index. **Not changed:** at demo scale this is cheap, and the better fix is a product decision. Options: cap the
count ("10,000+"), return it only on the first page, cache it per ward (the cache is already invalidated through
`carevoice:cache-invalidation`), or replace `X-Total-Count` with a `hasMore` flag.

### Finding 3: generic plans cannot use partial indexes

The planner can use a partial index only when it can *prove* the index's condition: `status = 'NEEDS_REVIEW'`
proves it, `status = $3` does not. pgjdbc switches to server-side prepared statements after five executions.
PostgreSQL then compares a generic plan with the custom ones (`plan_cache_mode = auto`). Here it keeps the custom plan,
because the generic one is much more expensive. With a forced generic plan, the review queue reads 1,558 rows
instead of 22. **Safe today.** If the review queue ever gets slow in production, check `plan_cache_mode` first; the
robust fix is to write the literal into the SQL for that one condition.

### Finding 4: the partial review index ends at `recorded_at`

The review index has no `id DESC` at the end, so rows with the same `recorded_at` need an *incremental sort*. That
costs 0.021 ms against 0.017 ms with `id` added. **Not changed:** the difference is noise, and not worth a migration.
If the index is ever rebuilt, add `id DESC` so it matches the keyset order exactly.

## 5. Write cost

- At 1M rows, `observations` has 170 MB of table data and 170 MB of indexes (resident 65 MB, ward 56 MB, pkey
  30 MB, transcript 19 MB, review 0.4 MB). Each insert writes the row plus four index entries (five for a row that
  needs review). Saving a transcript is one transaction (its observations plus one outbox row), well within that
  budget.
- **HOT updates.** PostgreSQL can update a row in place on the same page without touching any index (a HOT update)
  only when no indexed column changes. A column in a partial index's `WHERE` counts as indexed. Confirming an
  observation changes `status`, which the review index's condition uses, so confirmations are not HOT. That is
  accepted: they are rare, and a fast review queue is worth more. The outbox lease updates `next_attempt_at`
  (indexed), so those updates are not HOT either; the rows live only briefly, and autovacuum cleans them up.
- **Indexes not added on purpose:** no index on `vital_type` (three values, never selective, filtered while walking the
  ward index), and no GIN index on `outbox.payload` (never queried, only published).

## 6. Guard rails in CI

`QueryPlanTest` (documentation-service) wraps the `DataSource` in `ExplainingDataSource`. Every `SELECT`/`WITH`
that the repositories send is first EXPLAINed on the same connection with the same parameters, then runs normally.
The tests seed 100k observations shaped like the benchmark, run `VACUUM ANALYZE`, call the real repository methods
and check:

| Test | Asserts |
|---|---|
| Resident history, first page | `observations_resident_recorded_idx`, no `Sort`, no `Seq Scan` |
| Resident count | an index, no `Seq Scan` |
| Ward list, keyset page | `observations_ward_recorded_idx`, no `Sort`, the row comparison inside the `Index Cond` |
| Ward list, vital filter | ward index, no `Sort` |
| Review queue | `observations_needs_review_idx`, no `Seq Scan` |
| SSE replay | `observations_pkey` + ward index, no `Sort` |
| Outbox claim and gauges | `outbox_pending_idx`, no `Seq Scan` |

`AlertQueryPlanTest` (alert-service) captures the repository's `find` commands with a MongoDB `CommandListener` and
runs them again as `explain` (executionStats). Open alerts must use `ix_alerts_scope_status_created`, with no
`COLLSCAN` and no in-memory `SORT`, and must examine exactly as many documents as they return. A deep keyset page
must use `ix_alerts_scope_created` and examine fewer than 60 keys, although 300 alerts lie before the cursor.

The tests check access paths, never timings. Timings vary by machine and belong in the benchmark.

## 7. Operating it

- **Unused indexes:** `SELECT relname, indexrelname, idx_scan FROM pg_stat_user_indexes ORDER BY idx_scan;` (an index
  at 0 after weeks of traffic is pure write cost). MongoDB: `db.alerts.aggregate([{ $indexStats: {} }])`.
- **Slow queries:** `pg_stat_statements` (top total time), `auto_explain.log_min_duration` for plans of slow
  statements; MongoDB profiler at `slowms`.
- **Estimate drift:** compare `rows` with `actual rows` in `EXPLAIN ANALYZE`. A gap of 10× or more means statistics
  (finding 1), not a missing index.
- **Adding an index in production:** `CREATE INDEX CONCURRENTLY` in a migration of its own, with Flyway's
  `executeInTransaction=false` (it cannot run in a transaction). It does not block writes but takes longer, and on
  failure it leaves an `INVALID` index that must be dropped. MongoDB builds indexes without blocking since 4.2.

## 8. Reproduce

```bash
make up-fast                              # stack with migrations applied (V3 included)
make db-bench                             # 1M observations, 300k alerts → build/db-bench/report.md
ROWS=200000 ALERTS=50000 make db-bench    # smaller and faster
./gradlew :documentation-service:test --tests '*QueryPlanTest'
./gradlew :alert-service:test --tests '*AlertQueryPlanTest'
```

The benchmark works in throwaway places (schema `carevoice_bench`, database `carevoice_bench`) and drops them
afterwards. From the application's own data it only reads the table and index definitions, so its indexes always
match the migrations.
