# 4. PostgreSQL for documentation-service

- Status: Accepted
- Date: 2026-10-25

## Context

documentation-service owns the care record: residents, transcripts, observations, the outbox and the audit log. In
a care record, a wrong or half-written value is worse than a missing one. Saving a transcript must write the
observations *and* the events about them in one step.

## Decision

Use PostgreSQL (17 in docker compose, 18 in Kubernetes with CloudNativePG), accessed with plain JDBC and HikariCP.
Flyway manages the schema.

Each reason below is used by the code:

- **ACID transactions**: the transcript, its observations, one outbox row per observation and the audit row are
  saved in one transaction (Unit of Work pattern). This is what makes the transactional outbox possible (ADR-0006).
- **A strict schema**: `NOT NULL`, foreign keys and `CHECK` constraints check the data a second time, below the
  domain's own validation.
- **`FOR UPDATE SKIP LOCKED`**: several relay pods take outbox rows at the same time without blocking each other.
- **Row-value comparisons** like `(recorded_at, id) < (?, ?)`: these power keyset cursor pagination (ADR-0011).
- **Partial indexes** (for example only on `PENDING` outbox rows) and **JSONB** for event payloads and stored
  idempotent responses. Every index, the query it serves and its measured effect: [docs/indexing.md](../indexing.md)
  (ADR-0024).
- **Advisory locks**: only one pod at a time runs the transcript retention job and the demo seeder (ADR-0013).
- **A `version` column** for optimistic locking (ETag and If-Match, ADR-0011).
- **Time limits on the application role** (migration `V4`): `statement_timeout = 5s` and
  `idle_in_transaction_session_timeout = 15s`. A slow statement or a stuck transaction is stopped by the database
  and becomes a 503, instead of holding a connection forever. These limits work behind PgBouncer too.

## Consequences

- Strong consistency where it matters most.
- Schema changes need migrations. They are written expand/contract (add first, remove later) and never edited after
  they were applied.
- Many pods, each with a connection pool, can use up Postgres connections. PgBouncer solves this in Kubernetes
  (ADR-0016).
- A replica protects against a lost server, not against a bad write. Backups with point-in-time recovery cover that
  (ADR-0026).

## Alternatives considered

- **MongoDB for everything**: it has multi-document transactions, but they came later. `SKIP LOCKED`-style work
  queues, row-value comparisons and `CHECK` constraints have no direct equivalent.
- **MySQL**: workable, and it has `SKIP LOCKED`, but partial indexes and JSON support are weaker.
