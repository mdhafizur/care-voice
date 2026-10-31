-- Planner statistics for the scope columns every observation query filters on (docs/indexing.md, finding 1).
--
-- tenant_id, ward_id and resident_id are functionally dependent: a resident lives in exactly one ward of one tenant.
-- Without being told, the planner treats them as independent and multiplies their selectivities, so for one resident's
-- history (WHERE tenant_id = ? AND ward_id = ? AND resident_id = ?) it estimated 1 row instead of ~400. With that
-- estimate it chose a BitmapAnd of the resident AND the ward index followed by a sort, and never used the order of
-- observations_resident_recorded_idx. Dependency statistics correct the estimate; the plan becomes an ordered index
-- scan that stops after LIMIT rows (scripts/db-bench: 298 → 11 buffers, ~2 ms → 0.04 ms at 1M rows).
--
-- Metadata only: no table rewrite, no index build. CREATE STATISTICS and ANALYZE take SHARE UPDATE EXCLUSIVE, which
-- does not block reads or writes. Autovacuum's ANALYZE keeps the statistics current from here on.
CREATE STATISTICS observations_scope_deps (dependencies) ON tenant_id, ward_id, resident_id FROM observations;
ANALYZE observations;
