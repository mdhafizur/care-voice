-- Index benchmark for documentation-service (docs/indexing.md). Run with `make db-bench`, or directly:
--   psql -v rows=1000000 -v src=public -f scripts/db-bench/postgres.sql
--
-- What it does
--   1. Copies the production table definitions (columns, defaults, CHECK constraints) of `observations` and `outbox`
--      from schema :src into a throwaway schema `carevoice_bench`, so application data is never touched.
--   2. Seeds :rows observations (2,500 residents in 50 wards of 5 tenants, one year, three vitals per transcript)
--      and one outbox row per transcript, deterministically (same numbers on every run).
--   3. Recreates exactly the indexes and extended statistics the migrations created in :src (read from the catalog,
--      so the benchmark cannot drift from the schema).
--   4. Runs the queries the repositories send, EXPLAIN (ANALYZE, BUFFERS) seven times each, with the deployed indexes
--      and with alternatives (index dropped, different column order, partial vs full). Every alternative is created
--      inside a subtransaction that is rolled back afterwards, so variants never leak into each other.
--   5. Prints a Markdown report on stdout (median of runs 2..7; run 1 only warms the cache).
--
-- The queries are the repository SQL with literal values; `SELECT *` equals the repository's column list (it selects
-- every column), so the plans are the same.

\set ON_ERROR_STOP on
\set QUIET on
\if :{?rows}
\else
  \set rows 1000000
\endif
\if :{?src}
\else
  \set src public
\endif
\pset format unaligned
\pset tuples_only on
\pset footer off
SET client_min_messages = warning;

DROP SCHEMA IF EXISTS carevoice_bench CASCADE;
CREATE SCHEMA carevoice_bench;
-- Unqualified names below (and in the measured queries) resolve to the bench copies, like the application's do.
SET search_path = carevoice_bench;

CREATE TABLE observations (LIKE :"src".observations INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE TABLE outbox (LIKE :"src".outbox INCLUDING DEFAULTS INCLUDING CONSTRAINTS);

-- ---- Seed ------------------------------------------------------------------------------------------------------
-- i = observation number, t = i / 3 = transcript number (three vitals per transcript share recorded_at: ties that the
-- keyset's id tie-breaker has to resolve). Time runs forward with t, so the heap is in insertion order like a real
-- table. Residents are spread pseudo-randomly over transcripts (t * 7919 mod 2500). 1 % of rows need review.
INSERT INTO observations (id, tenant_id, ward_id, resident_id, transcript_id, vital_type, systolic, diastolic,
                          pulse_bpm, temperature_celsius, source, confidence, status, recorded_at, version)
SELECT md5('obs' || i)::uuid,
       'bench-tenant-' || (r / 500),
       'bench-ward-' || lpad((r / 50)::text, 2, '0'),
       'bench-resident-' || lpad(r::text, 4, '0'),
       md5('tr' || t)::uuid,
       v.vital_type,
       CASE WHEN v.vital_type = 'BLOOD_PRESSURE' THEN 110 + i % 40 END,
       CASE WHEN v.vital_type = 'BLOOD_PRESSURE' THEN 70 + i % 20 END,
       CASE WHEN v.vital_type = 'PULSE' THEN 60 + i % 40 END,
       CASE WHEN v.vital_type = 'BODY_TEMPERATURE' THEN 36.0 + (i % 30) / 10.0 END,
       CASE WHEN i % 4 = 0 THEN 'AI' ELSE 'RULES' END,
       CASE WHEN i % 4 = 0 THEN 0.70 ELSE 0.95 END,
       CASE WHEN (i::bigint * 2654435761) % 100 = 0 THEN 'NEEDS_REVIEW' ELSE 'CONFIRMED' END,
       timestamptz '2025-10-01 00:00:00+00' + t * (interval '365 days' / greatest(:rows / 3, 1)),
       0
  FROM generate_series(0, :rows - 1) AS i,
       LATERAL (SELECT i / 3 AS t) AS tt,
       LATERAL (SELECT ((i / 3)::bigint * 7919 % 2500)::int AS r) AS rr,
       LATERAL (SELECT (ARRAY['BLOOD_PRESSURE', 'PULSE', 'BODY_TEMPERATURE'])[i % 3 + 1] AS vital_type) AS v;

-- One event per transcript. Nearly all PUBLISHED (the table only grows); 100 due, 50 waiting for a retry, 20 DEAD:
-- the shape the relay's claim query sees in production.
INSERT INTO outbox (id, aggregate_id, event_type, payload, status, attempts, next_attempt_at, created_at, published_at)
SELECT md5('ev' || t)::uuid,
       md5('tr' || t)::uuid,
       'ObservationsRecorded',
       jsonb_build_object('transcriptId', md5('tr' || t)::uuid, 'observations', 3),
       CASE WHEN t >= n - 150 THEN 'PENDING' WHEN t BETWEEN n - 1000 AND n - 981 THEN 'DEAD' ELSE 'PUBLISHED' END,
       CASE WHEN t >= n - 50 THEN 3 ELSE 0 END,
       CASE WHEN t >= n - 50 THEN timestamptz '2026-10-01 01:00:00+00'
            ELSE timestamptz '2025-10-01 00:00:00+00' + t * (interval '365 days' / n) END,
       timestamptz '2025-10-01 00:00:00+00' + t * (interval '365 days' / n),
       CASE WHEN t < n - 150 THEN timestamptz '2025-10-01 00:00:01+00' + t * (interval '365 days' / n) END
  FROM (SELECT greatest(:rows / 3, 1) AS n) AS params,
       generate_series(0, greatest(:rows / 3, 1) - 1) AS t;

-- The production indexes, exactly as the migrations created them in :src.
SELECT replace(indexdef, ' ON ' || :'src' || '.', ' ON carevoice_bench.')
  FROM pg_indexes
 WHERE schemaname = :'src' AND tablename IN ('observations', 'outbox')
 ORDER BY indexname
\gexec

-- Extended statistics objects (CREATE STATISTICS, migration V3), re-created under their own names in the bench schema.
SELECT replace(pg_get_statisticsobjdef(s.oid), ' ' || :'src' || '.', ' carevoice_bench.')
  FROM pg_statistic_ext AS s
 WHERE s.stxnamespace = to_regnamespace(:'src') AND s.stxrelid IN (to_regclass(:'src' || '.observations'),
                                                                   to_regclass(:'src' || '.outbox'))
\gexec

-- Statistics and the visibility map, as autovacuum would have them on a table of this age.
VACUUM (ANALYZE) observations;
VACUUM (ANALYZE) outbox;

-- ---- Measurement -------------------------------------------------------------------------------------------------
CREATE TABLE results (
    seq            serial,
    section        text,
    variant        text,
    access_path    text,
    rows_examined  bigint,
    rows_returned  bigint,
    buffers        bigint,
    median_ms      numeric,
    index_mb       numeric
);

-- Every plan node of an EXPLAIN (FORMAT JSON) document, in document order (outermost first).
CREATE FUNCTION plan_nodes(plan jsonb) RETURNS SETOF jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT node FROM jsonb_path_query(plan, 'strict $.** ? (exists (@."Node Type"))') AS node
$$;

CREATE FUNCTION measure(p_section text, p_variant text, p_query text, p_setup text DEFAULT NULL, p_runs int DEFAULT 7)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    plan      jsonb;
    times     numeric[] := '{}';
    v_path    text;
    v_rows    bigint;
    v_ret     bigint;
    v_buffers bigint;
    v_index   numeric;
BEGIN
    -- Outer subtransaction: the variant's DDL (DROP/CREATE INDEX) lives only inside it.
    BEGIN
        IF p_setup IS NOT NULL THEN
            EXECUTE p_setup;
        END IF;
        FOR run IN 1..p_runs LOOP
            -- Inner subtransaction per run: EXPLAIN ANALYZE executes the statement, so the outbox claim's UPDATE
            -- must be undone before the next run sees the table. Variables survive the rollback.
            BEGIN
                EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' || p_query INTO plan;
                RAISE EXCEPTION USING ERRCODE = 'CVB01';
            EXCEPTION WHEN SQLSTATE 'CVB01' THEN
                NULL;
            END;
            IF run > 1 THEN
                times := times || (plan -> 0 ->> 'Execution Time')::numeric;
            END IF;
        END LOOP;

        SELECT string_agg(
                   CASE WHEN n ? 'Index Name' THEN (n ->> 'Node Type') || ' · ' || (n ->> 'Index Name')
                        ELSE n ->> 'Node Type' END,
                   ' → ' ORDER BY ord),
               coalesce(sum(CASE WHEN n ->> 'Node Type' <> 'Bitmap Index Scan' AND n ->> 'Node Type' LIKE '%Scan'
                                 THEN ((n ->> 'Actual Rows')::numeric
                                       + coalesce((n ->> 'Rows Removed by Filter')::numeric, 0)
                                       + coalesce((n ->> 'Rows Removed by Index Recheck')::numeric, 0))
                                      * (n ->> 'Actual Loops')::numeric END), 0)
          INTO v_path, v_rows
          FROM plan_nodes(plan) WITH ORDINALITY AS nodes(n, ord)
         WHERE n ->> 'Node Type' LIKE '%Scan' OR n ->> 'Node Type' LIKE '%Sort'
            OR n ->> 'Node Type' IN ('Gather', 'BitmapAnd', 'BitmapOr');

        SELECT round(sum(pg_relation_size(to_regclass(name))) / 1048576.0, 1)
          INTO v_index
          FROM (SELECT DISTINCT n ->> 'Index Name' AS name FROM plan_nodes(plan) AS n WHERE n ? 'Index Name') AS used;

        RAISE EXCEPTION USING ERRCODE = 'CVB02';
    EXCEPTION WHEN SQLSTATE 'CVB02' THEN
        NULL;
    END;

    v_ret := (plan -> 0 -> 'Plan' ->> 'Actual Rows')::bigint;
    v_buffers := coalesce((plan -> 0 -> 'Plan' ->> 'Shared Hit Blocks')::bigint, 0)
               + coalesce((plan -> 0 -> 'Plan' ->> 'Shared Read Blocks')::bigint, 0);
    INSERT INTO results (section, variant, access_path, rows_examined, rows_returned, buffers, median_ms, index_mb)
    VALUES (p_section, p_variant, v_path, v_rows, v_ret, v_buffers,
            (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY x) FROM unnest(times) AS x), v_index);
END;
$$;

-- One sample scope: resident 0042 lives in ward 00 of tenant 0 (each ward holds 2 % of all observations).
\set tenant '''bench-tenant-0'''
\set ward '''bench-ward-00'''
\set resident '''bench-resident-0042'''

-- Cursor for a deep page of the ward list (20 per page): row 10,000, or the middle of the ward for small seeds.
SELECT least(9999, count(*) / 2) AS skip FROM observations WHERE tenant_id = :tenant AND ward_id = :ward
\gset
SELECT recorded_at AS cursor_at, id AS cursor_id
  FROM observations
 WHERE tenant_id = :tenant AND ward_id = :ward
 ORDER BY recorded_at DESC, id DESC
OFFSET :skip LIMIT 1
\gset
SELECT format('B. Ward list, page %s (20 per page)', :skip / 20 + 1) AS section_b,
       format('OFFSET %s (not used: shown for comparison)', :skip) AS offset_variant
\gset

-- A. Resident history, first page: GET /v1/residents/{id}/observations (listByResident).
SELECT measure('A. Resident history, first page', 'deployed: (resident_id, recorded_at DESC, id DESC) + statistics',
    format('SELECT * FROM observations WHERE tenant_id = %L AND ward_id = %L AND resident_id = %L
            ORDER BY recorded_at DESC, id DESC LIMIT 21', :tenant, :ward, :resident));
SELECT measure('A. Resident history, first page', 'without extended statistics (before V3): estimate 1 row',
    format('SELECT * FROM observations WHERE tenant_id = %L AND ward_id = %L AND resident_id = %L
            ORDER BY recorded_at DESC, id DESC LIMIT 21', :tenant, :ward, :resident),
    'DROP STATISTICS IF EXISTS observations_scope_deps');
SELECT measure('A. Resident history, first page', 'single-column index on (resident_id): must sort',
    format('SELECT * FROM observations WHERE tenant_id = %L AND ward_id = %L AND resident_id = %L
            ORDER BY recorded_at DESC, id DESC LIMIT 21', :tenant, :ward, :resident),
    'DROP INDEX observations_resident_recorded_idx; CREATE INDEX bench_resident_only_idx ON observations (resident_id)');
SELECT measure('A. Resident history, first page', 'no resident index: falls back to the ward index',
    format('SELECT * FROM observations WHERE tenant_id = %L AND ward_id = %L AND resident_id = %L
            ORDER BY recorded_at DESC, id DESC LIMIT 21', :tenant, :ward, :resident),
    'DROP INDEX observations_resident_recorded_idx');
SELECT measure('A. Resident history, first page', 'no observation indexes at all',
    format('SELECT * FROM observations WHERE tenant_id = %L AND ward_id = %L AND resident_id = %L
            ORDER BY recorded_at DESC, id DESC LIMIT 21', :tenant, :ward, :resident),
    'DROP INDEX observations_resident_recorded_idx, observations_ward_recorded_idx, observations_needs_review_idx');

-- B. Ward list, page 500: QUERY /v1/observations. Keyset (what the repository does) against OFFSET.
SELECT measure(:'section_b', 'keyset: (recorded_at, id) < cursor (deployed)',
    format('SELECT * FROM observations WHERE tenant_id = %L AND ward_id = %L AND (recorded_at, id) < (%L, %L)
            ORDER BY recorded_at DESC, id DESC LIMIT 21', :tenant, :ward, :'cursor_at', :'cursor_id'));
SELECT measure(:'section_b', :'offset_variant',
    format('SELECT * FROM observations WHERE tenant_id = %L AND ward_id = %L
            ORDER BY recorded_at DESC, id DESC OFFSET %s LIMIT 21', :tenant, :ward, :skip));
SELECT measure(:'section_b', 'keyset without the ward index',
    format('SELECT * FROM observations WHERE tenant_id = %L AND ward_id = %L AND (recorded_at, id) < (%L, %L)
            ORDER BY recorded_at DESC, id DESC LIMIT 21', :tenant, :ward, :'cursor_at', :'cursor_id'),
    'DROP INDEX observations_ward_recorded_idx');

-- C. Review queue: QUERY /v1/observations with status = NEEDS_REVIEW (1 % of rows).
SELECT measure('C. Review queue (status = NEEDS_REVIEW)', 'deployed: partial (ward_id, recorded_at DESC)',
    format('SELECT * FROM observations WHERE tenant_id = %L AND ward_id = %L AND status = %L
            ORDER BY recorded_at DESC, id DESC LIMIT 21', :tenant, :ward, 'NEEDS_REVIEW'));
SELECT measure('C. Review queue (status = NEEDS_REVIEW)', 'partial index with id DESC appended',
    format('SELECT * FROM observations WHERE tenant_id = %L AND ward_id = %L AND status = %L
            ORDER BY recorded_at DESC, id DESC LIMIT 21', :tenant, :ward, 'NEEDS_REVIEW'),
    'DROP INDEX observations_needs_review_idx; CREATE INDEX bench_needs_review_id_idx ON observations
     (ward_id, recorded_at DESC, id DESC) WHERE status = ''NEEDS_REVIEW''');
SELECT measure('C. Review queue (status = NEEDS_REVIEW)', 'no partial index: ward index + filter',
    format('SELECT * FROM observations WHERE tenant_id = %L AND ward_id = %L AND status = %L
            ORDER BY recorded_at DESC, id DESC LIMIT 21', :tenant, :ward, 'NEEDS_REVIEW'),
    'DROP INDEX observations_needs_review_idx');

-- D. X-Total-Count: the count(*) every list page runs for its scope.
SELECT measure('D. X-Total-Count for a ward', 'deployed: ward index, heap visited for tenant_id',
    format('SELECT count(*) FROM observations WHERE tenant_id = %L AND ward_id = %L', :tenant, :ward));
SELECT measure('D. X-Total-Count for a ward', '(tenant_id, ward_id, recorded_at DESC, id DESC): index-only',
    format('SELECT count(*) FROM observations WHERE tenant_id = %L AND ward_id = %L', :tenant, :ward),
    'CREATE INDEX bench_tenant_ward_idx ON observations (tenant_id, ward_id, recorded_at DESC, id DESC)');

-- E. Outbox relay: the claim (CTE + FOR UPDATE SKIP LOCKED + UPDATE ... RETURNING) and the gauges' stats query.
SELECT measure('E. Outbox claim (50 due rows)', 'deployed: partial (next_attempt_at) WHERE PENDING',
    $q$WITH due AS (SELECT id FROM outbox WHERE status = 'PENDING' AND next_attempt_at <= '2026-10-01 00:00:00+00'
                    ORDER BY next_attempt_at LIMIT 50 FOR UPDATE SKIP LOCKED)
       UPDATE outbox o SET next_attempt_at = '2026-10-01 00:00:30+00' FROM due WHERE o.id = due.id
       RETURNING o.id, o.aggregate_id, o.event_type, o.payload::text AS payload, o.attempts, o.created_at$q$);
SELECT measure('E. Outbox claim (50 due rows)', 'full index on (status, next_attempt_at)',
    $q$WITH due AS (SELECT id FROM outbox WHERE status = 'PENDING' AND next_attempt_at <= '2026-10-01 00:00:00+00'
                    ORDER BY next_attempt_at LIMIT 50 FOR UPDATE SKIP LOCKED)
       UPDATE outbox o SET next_attempt_at = '2026-10-01 00:00:30+00' FROM due WHERE o.id = due.id
       RETURNING o.id, o.aggregate_id, o.event_type, o.payload::text AS payload, o.attempts, o.created_at$q$,
    'DROP INDEX outbox_pending_idx; CREATE INDEX bench_outbox_full_idx ON outbox (status, next_attempt_at)');
SELECT measure('E. Outbox claim (50 due rows)', 'no index',
    $q$WITH due AS (SELECT id FROM outbox WHERE status = 'PENDING' AND next_attempt_at <= '2026-10-01 00:00:00+00'
                    ORDER BY next_attempt_at LIMIT 50 FOR UPDATE SKIP LOCKED)
       UPDATE outbox o SET next_attempt_at = '2026-10-01 00:00:30+00' FROM due WHERE o.id = due.id
       RETURNING o.id, o.aggregate_id, o.event_type, o.payload::text AS payload, o.attempts, o.created_at$q$,
    'DROP INDEX outbox_pending_idx');
SELECT measure('F. Outbox gauges (pending count, oldest)', 'deployed: partial index',
    $q$SELECT count(*), min(created_at) FROM outbox WHERE status = 'PENDING'$q$);
SELECT measure('F. Outbox gauges (pending count, oldest)', 'no index',
    $q$SELECT count(*), min(created_at) FROM outbox WHERE status = 'PENDING'$q$,
    'DROP INDEX outbox_pending_idx');

-- F. SSE replay after reconnect (findAfter): everything newer than the last event id the client saw.
SELECT id AS replay_after FROM observations
 WHERE tenant_id = :tenant AND ward_id = :ward
 ORDER BY recorded_at DESC, id DESC OFFSET 50 LIMIT 1
\gset
SELECT measure('G. SSE replay (Last-Event-ID)', 'deployed: pkey for the cursor row, ward index scanned backward',
    format('SELECT * FROM observations WHERE tenant_id = %L AND ward_id = %L
              AND (recorded_at, id) > (SELECT recorded_at, id FROM observations
                                        WHERE tenant_id = %L AND ward_id = %L AND id = %L)
            ORDER BY recorded_at ASC, id ASC LIMIT 100', :tenant, :ward, :tenant, :ward, :'replay_after'));

-- ---- Report --------------------------------------------------------------------------------------------------------
\echo '# Index benchmark: documentation-service'
\echo
SELECT format('%s observations, %s outbox rows · %s · %s', to_char(:rows, 'FM9,999,999,999'),
              to_char((SELECT count(*) FROM outbox), 'FM9,999,999,999'),
              split_part(version(), ' on ', 1), to_char(now(), 'YYYY-MM-DD HH24:MI TZ'));
\echo
\echo 'Median of runs 2-7 of EXPLAIN (ANALYZE, BUFFERS). Rows examined = rows the scans read, including the ones'
\echo 'filters threw away. Buffers = 8 kB pages touched. Index MB = size of the indexes the plan used.'
\echo

SELECT string_agg(block, E'\n\n' ORDER BY first_seq)
  FROM (SELECT min(seq) AS first_seq,
               '## ' || section || E'\n\n'
               || E'| Variant | Plan | Rows examined | Rows | Buffers | Index MB | Median ms |\n'
               || E'|---|---|--:|--:|--:|--:|--:|\n'
               || string_agg(format('| %s | %s | %s | %s | %s | %s | %s |', variant, access_path,
                                    to_char(rows_examined, 'FM999,999,999'), rows_returned,
                                    to_char(buffers, 'FM999,999,999'), coalesce(index_mb::text, '–'),
                                    to_char(median_ms, 'FM999,990.000')), E'\n' ORDER BY seq) AS block
          FROM results
         GROUP BY section) AS sections;

\echo
\echo '## Index sizes'
\echo
\echo '| Index | Size MB | Rows indexed |'
\echo '|---|--:|--:|'
SELECT format('| %s | %s | %s |', c.relname, round(pg_relation_size(c.oid) / 1048576.0, 1),
              to_char(c.reltuples, 'FM999,999,999'))
  FROM pg_class AS c
  JOIN pg_index AS i ON i.indexrelid = c.oid
 WHERE c.relnamespace = 'carevoice_bench'::regnamespace
 ORDER BY pg_relation_size(c.oid) DESC;
\echo
SELECT format('Tables: observations %s MB, outbox %s MB (heap only).',
              round(pg_relation_size('observations') / 1048576.0), round(pg_relation_size('outbox') / 1048576.0));

\if :{?keep}
\else
  DROP SCHEMA carevoice_bench CASCADE;
\endif
