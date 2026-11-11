# 5. MongoDB for alert-service

- Status: Accepted
- Date: 2026-10-25

## Context

Alerts are derived data: each one comes from an `ObservationRecordedEvent`, and all of them could be rebuilt by
replaying the events. An event consumer writes them once, and it receives every event *at least once*. Alerts are
then read as a ward list and sometimes acknowledged. New rules and vital types will add fields over time.

## Decision

Use MongoDB 8 with the Kotlin coroutine driver.

- **One self-contained document per alert** (vital, rule, severity, message, status): no joins, one read.
- **A flexible shape**: a new rule or vital type adds fields without migrating old alerts.
- **Idempotency through a unique index** on `eventId + rule`. The code inserts and catches the duplicate-key error;
  it never checks first and then inserts. This makes at-least-once delivery safe (ADR-0008).
- **A TTL index** on `processed_events.processedAt` (30 days): MongoDB deletes old deduplication records by itself.
- **Two compound indexes for the ward list and its cursor**: `{tenantId, wardId, status, createdAt: -1, _id: -1}`
  for a list filtered by status, and `{tenantId, wardId, createdAt: -1, _id: -1}` without the filter.
- **A replica set `rs0`**: 1 member in docker compose, 3 in Kubernetes. With 3 members a new primary is elected
  automatically when one fails. Writes use `writeConcern: majority`, so an acknowledged alert survives losing the
  primary. The driver retries a write once after a failover, and a failover the client still sees becomes
  503 + `Retry-After`.
- **Schema changes (indexes) through a versioned migration runner** (Command pattern) with a lock document. The
  lock is always released, even when the service stops during a migration. Index shapes follow the ESR rule
  (equality, sort, range) and are measured and plan-tested: [docs/indexing.md](../indexing.md).
- **Also the store of the protocol knowledge base** for alert guidance (`protocol_chunks`, ADR-0023): text chunks
  with their embedding vectors as arrays, ranked in memory. MongoDB Community has no vector index, and a few dozen
  chunks do not need one.

## Consequences

- The two services cannot share tables by accident: each service has its own database, enforced by technology.
- When MongoDB is down for longer, the event consumer pauses and the events wait in RabbitMQ (ADR-0007); nothing is
  lost.
- **Cost: two databases to run, back up and monitor.** MongoDB has no backups yet (only PostgreSQL does, ADR-0026).
  For a real product of this size I would start with PostgreSQL (JSONB) for both and split only when the access
  patterns really differ. The split here is on purpose, to show choosing a store per access pattern.

## Alternatives considered

- **PostgreSQL with JSONB**: fully good enough, and one system fewer. The honest default for production.
- **Redis as the alert store**: no durable way to query the ward list.
