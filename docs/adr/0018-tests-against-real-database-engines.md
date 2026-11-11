# 18. Tests run against real database engines, embedded by default

- Status: Accepted
- Date: 2026-10-25

## Context

The schema and the queries rely on PostgreSQL features (`SKIP LOCKED`, partial indexes, JSONB, row-value
comparisons, CHECK constraints, role-level time limits) and on MongoDB unique and TTL indexes. Tests must run fast
on a laptop, also without Docker, and must behave the same as in CI.

## Decision

- Real engines only. `TestDatabases` in `test-support` starts one instance per test JVM, shared by all test classes.
  Tests are isolated by unique tenant and ward ids per test, not by fresh databases.
- `CAREVOICE_TEST_DB` chooses the backend:
  - `embedded` (default, no Docker needed): zonky embedded-postgres, flapdoodle `mongod` and embedded `redis-server`.
    These are the real binaries, started as local processes (the JVM version of MongoMemoryServer).
  - `containers` (CI): Testcontainers with the same images as docker compose.
- **RabbitMQ always runs in a container.** No embedded broker behaves like RabbitMQ (quorum queues, dead-lettering,
  delivery limits, message TTLs). These tests are skipped, not failed, when Docker is not available.
- Fault-injection tests (WireMock faults, Toxiproxy) are tagged and run on demand.

## Consequences

- A test that passes has run the exact SQL that production runs.
- A few seconds of startup per JVM, paid once.
- The embedded MongoDB is a standalone server, so tests do not depend on replica-set features.

## Alternatives considered

- **H2 in PostgreSQL mode**: rejects or silently changes exactly the SQL that matters, so tests would pass where
  production fails.
- **Only mocked repositories**: fast, but they prove nothing about queries, indexes or transactions.
