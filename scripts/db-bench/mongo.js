// Index benchmark for alert-service (docs/indexing.md). Run with `make db-bench`, or directly:
//   mongosh --quiet --eval "const ALERTS = 300000" scripts/db-bench/mongo.js
//
// Seeds a throwaway database `carevoice_bench` with the production index definitions (read from the live alerts
// collection, so the benchmark cannot drift from migration M001), runs the repository's list queries with
// explain("executionStats") under the deployed indexes and under alternatives forced with hint(), and prints a
// Markdown report. The source database is only read, never written.

/* global db, print */
const N = typeof ALERTS === 'number' ? ALERTS : 300000;
const SOURCE = typeof SOURCE_DB === 'string' ? SOURCE_DB : 'carevoice_alerts';
const RUNS = 7;

const bench = db.getSiblingDB('carevoice_bench');
bench.dropDatabase();
const alerts = bench.getCollection('alerts');

// ---- Seed ----------------------------------------------------------------------------------------------------
// Same scopes as the PostgreSQL benchmark: 2,500 residents in 50 wards of 5 tenants, one year. 3 % of alerts are
// still OPEN (nurses acknowledge them), the rest ACKNOWLEDGED: the open-alerts query asks for a small slice.
const start = new Date('2025-10-01T00:00:00Z').getTime();
const step = (365 * 24 * 3600 * 1000) / N;
const rules = ['HYPERTENSIVE_CRISIS', 'HYPOTENSION', 'TACHYCARDIA', 'BRADYCARDIA', 'FEVER', 'HYPOTHERMIA'];
const pad = (n, width) => String(n).padStart(width, '0');
let batch = [];
for (let i = 0; i < N; i++) {
  const r = (i * 7919) % 2500;
  const createdAt = new Date(start + i * step);
  const open = (i * 2654435761) % 100 < 3;
  batch.push({
    _id: `bench-alert-${pad(i, 7)}`,
    tenantId: `bench-tenant-${Math.floor(r / 500)}`,
    wardId: `bench-ward-${pad(Math.floor(r / 50), 2)}`,
    residentId: `bench-resident-${pad(r, 4)}`,
    observationId: `bench-observation-${i}`,
    eventId: `bench-event-${i}`,
    rule: rules[i % rules.length],
    severity: i % 5 === 0 ? 'CRITICAL' : 'WARNING',
    message: 'Pulse 132 bpm is above 120 bpm',
    vital: { type: 'PULSE', bpm: 132 },
    status: open ? 'OPEN' : 'ACKNOWLEDGED',
    observedAt: createdAt,
    createdAt,
    acknowledgedBy: open ? null : 'bench-nurse',
    acknowledgedAt: open ? null : new Date(createdAt.getTime() + 600000),
    version: open ? 0 : 1,
  });
  if (batch.length === 10000) {
    alerts.insertMany(batch, { ordered: false });
    batch = [];
  }
}
if (batch.length) alerts.insertMany(batch, { ordered: false });

// The production indexes, copied from the live collection; the definition of M001 if the stack has none yet.
let specs = db.getSiblingDB(SOURCE).getCollection('alerts').getIndexes().filter((ix) => ix.name !== '_id_');
if (specs.length === 0) {
  specs = [
    { key: { eventId: 1, rule: 1 }, name: 'ux_alerts_event_rule', unique: true },
    { key: { tenantId: 1, wardId: 1, status: 1, createdAt: -1, _id: -1 }, name: 'ix_alerts_scope_status_created' },
    { key: { tenantId: 1, wardId: 1, createdAt: -1, _id: -1 }, name: 'ix_alerts_scope_created' },
  ];
}
for (const spec of specs) {
  const options = { name: spec.name };
  if (spec.unique) options.unique = true;
  if (spec.partialFilterExpression) options.partialFilterExpression = spec.partialFilterExpression;
  if (spec.expireAfterSeconds !== undefined) options.expireAfterSeconds = spec.expireAfterSeconds;
  alerts.createIndex(spec.key, options);
}

// ---- Measurement -----------------------------------------------------------------------------------------------
const scope = { tenantId: 'bench-tenant-0', wardId: 'bench-ward-00' };
const newestFirst = { createdAt: -1, _id: -1 };
const results = [];

// Formats the winning plan as "STAGE · index → STAGE", outermost first; branches as STAGE(a + b). SBE plans (8.0) nest
// the logical plan under queryPlan. LIMIT and projections are left out: every variant has them.
const SKIP = new Set(['LIMIT', 'PROJECTION_SIMPLE', 'PROJECTION_DEFAULT', 'PROJECTION_COVERED']);
function stages(node) {
  if (node.queryPlan) return stages(node.queryPlan);
  const children = node.inputStage ? [node.inputStage] : node.inputStages || [];
  if (SKIP.has(node.stage) && children.length === 1) return stages(children[0]);
  const name = node.indexName ? `${node.stage} · ${node.indexName}` : node.stage;
  if (children.length === 0) return name;
  if (children.length === 1) return `${name} → ${stages(children[0])}`;
  return `${name}(${children.map(stages).join(' + ')})`;
}

function measure(section, variant, filter, hint, sort = newestFirst) {
  const times = [];
  let explain;
  for (let run = 1; run <= RUNS; run++) {
    let cursor = alerts.find(filter).sort(sort).limit(21);
    if (hint) cursor = cursor.hint(hint);
    explain = cursor.explain('executionStats');
    if (run > 1) times.push(explain.executionStats.executionTimeMillis);
  }
  times.sort((a, b) => a - b);
  const stats = explain.executionStats;
  results.push({
    section,
    variant,
    plan: stages(explain.queryPlanner.winningPlan),
    keys: stats.totalKeysExamined,
    docs: stats.totalDocsExamined,
    returned: stats.nReturned,
    ms: times[Math.floor(times.length / 2)],
  });
}

// A. Open alerts of a ward, first page: GET /v1/alerts?status=OPEN (the nurse's main screen).
const openFilter = { ...scope, status: 'OPEN' };
measure('A. Open alerts, first page', 'deployed: {tenantId, wardId, status, createdAt -1, _id -1} (ESR)', openFilter);
alerts.createIndex({ tenantId: 1, wardId: 1, createdAt: -1, _id: -1, status: 1 }, { name: 'bench_sort_before_equality' });
measure('A. Open alerts, first page', 'status after the sort keys (E-S-E instead of E-E-S)', openFilter,
  'bench_sort_before_equality');
alerts.dropIndex('bench_sort_before_equality');
measure('A. Open alerts, first page', 'scope index without status: filter on fetched documents', openFilter,
  'ix_alerts_scope_created');
measure('A. Open alerts, first page', 'no index (collection scan + in-memory sort)', openFilter, { $natural: 1 });

// B. All alerts, page 500 by keyset: the repository's `(createdAt, _id) < cursor` spelled out as $or.
const cursorDoc = alerts.find(scope).sort(newestFirst).skip(9999).limit(1).toArray()[0];
const olderThanCursor = {
  ...scope,
  $or: [
    { createdAt: { $lt: cursorDoc.createdAt } },
    { createdAt: cursorDoc.createdAt, _id: { $lt: cursorDoc._id } },
  ],
};
measure('B. All alerts, page 500 (keyset)', 'deployed: {tenantId, wardId, createdAt -1, _id -1}', olderThanCursor);
measure('B. All alerts, page 500 (keyset)', 'status index (status not filtered: every status branch merged)',
  olderThanCursor, 'ix_alerts_scope_status_created');
measure('B. All alerts, page 500 (keyset)', 'no index', olderThanCursor, { $natural: 1 });

// ---- Report ----------------------------------------------------------------------------------------------------
print('# Index benchmark: alert-service');
print('');
print(`${N.toLocaleString('en-US')} alerts · MongoDB ${db.version()} · ${new Date().toISOString().slice(0, 16)}Z`);
print('');
print('Median of runs 2-7 of explain("executionStats"). Keys/Docs = index keys and documents examined; the ideal');
print('is keys ≈ docs ≈ returned. SORT in the plan means an in-memory sort (the index did not deliver the order).');
let section = null;
for (const r of results) {
  if (r.section !== section) {
    section = r.section;
    print('');
    print(`## ${section}`);
    print('');
    print('| Variant | Plan | Keys examined | Docs examined | Returned | Median ms |');
    print('|---|---|--:|--:|--:|--:|');
  }
  print(`| ${r.variant} | ${r.plan} | ${r.keys.toLocaleString('en-US')} | ${r.docs.toLocaleString('en-US')} | ` +
    `${r.returned} | ${r.ms} |`);
}
print('');
print('## Index sizes');
print('');
print('| Index | Size MB |');
print('|---|--:|');
const sizes = alerts.aggregate([{ $collStats: { storageStats: {} } }]).toArray()[0].storageStats.indexSizes;
for (const name of Object.keys(sizes)) print(`| ${name} | ${(sizes[name] / 1048576).toFixed(1)} |`);

if (typeof KEEP === 'undefined') bench.dropDatabase();
