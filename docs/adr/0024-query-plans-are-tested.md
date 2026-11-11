# 24. Index design is measured and query plans are tested

- Status: Accepted
- Date: 2026-10-26

## Context

Indexes are usually designed once, by reading the queries, and then trusted. Two things break that trust without
anyone noticing. A query changes (a new filter, a different `ORDER BY`) and no longer matches its index. Or the
planner stops choosing the index because its row estimate is wrong. Functional tests catch neither: the query still
returns the right rows, only slower, and only at production volumes.

The benchmark showed that the second case is real here. The resident-history index
`(resident_id, recorded_at DESC, id DESC)` was not used for its order, because the planner estimated 1 row instead of
about 400 for the correlated `tenant_id`/`ward_id`/`resident_id` filter (docs/indexing.md, finding 1).

## Decision

- **Every index is documented with the query it serves** (docs/indexing.md, catalogue).
- **A benchmark measures each index against its alternatives** (`make db-bench`): 1M observations and 300k alerts in
  throwaway schemas. It runs EXPLAIN (ANALYZE, BUFFERS) and MongoDB `explain("executionStats")` with the deployed
  index, with the index dropped, and with indexes of a different shape. It reads the index and statistics
  definitions from the live schema, so the benchmark always matches the migrations. Each variant runs in a
  subtransaction that is rolled back.
- **CI checks access paths, not timings.** `QueryPlanTest` EXPLAINs the SQL that the repositories really send (a
  JDBC proxy, same connection and parameters). `AlertQueryPlanTest` runs the `find` commands that the repository
  really sends (captured with a MongoDB `CommandListener`) again as `explain`. Both use seeded data shaped like
  production. They check the index used, no sequential or collection scan, no sort where the index must deliver the
  order, and, for MongoDB, documents examined ≈ documents returned.
- **Planner statistics are part of the schema**: `V3__observation_scope_statistics.sql` adds dependency statistics on
  the scope columns, and `QueryPlanTest` guards them.

## Consequences

- A query or migration change that loses an index fails the build, with the plan in the message, instead of showing
  up as latency in production.
- The plan tests seed 100k rows (PostgreSQL) and 30k documents (MongoDB): a few seconds of test time.
- Plan tests depend on planner behaviour, which can change between major versions. So they check only robust
  properties (index used, no scan, no sort), and they run both on the embedded engines and on the production images
  (`make test-containers`). A version upgrade that changes a plan becomes visible.
- Timings stay out of CI, because they vary by machine. The benchmark report is the place for numbers.

## Alternatives considered

- **Copying the SQL into the tests**: simpler, but the copy drifts away from the repository, and the test then proves
  nothing.
- **`auto_explain` / `pg_stat_statements` in production only**: needed for operations (docs/indexing.md, section 7),
  but it finds problems only after users feel them.
- **Timing checks** ("query < 5 ms"): flaky across machines and CI runners, and still blind to plans that are fast on
  small data and slow on large data.
