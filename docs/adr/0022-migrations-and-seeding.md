# 22. Schema migrations and demo seeding

- Status: Accepted
- Date: 2026-10-25

## Context

Schemas change while pods of the old and the new version run side by side during a rolling deploy. Demo data must
exist locally, but must never reach production by accident.

## Decision

- **PostgreSQL: Flyway**, configured in code: validate on migrate, `clean` disabled, no out-of-order migrations, no
  baseline. Migration V4 gives the application role a 5 s statement limit; a later migration that needs longer must
  start with `SET LOCAL statement_timeout = 0`, which lasts only for its own transaction.
- **MongoDB: a versioned migration runner** (Command pattern): a `schema_migrations` collection with checksums,
  ordered migration classes, and a lock document with an expiry, so that concurrent pods apply each migration only
  once. The lock is always released, even when the process is shutting down. A pod refuses to start when the
  database is newer than the code.
- Both services accept a single `migrate` argument (run the migrations, then exit). The compose one-shot containers
  and the Kubernetes pre-upgrade Jobs use it. With `*_MIGRATE_ON_STARTUP=false`, a pod only validates the schema
  and refuses to start when a migration is pending or does not match.
- Rules: never edit an applied migration. Breaking changes use expand/contract over two releases, so old and new
  pods both work during the rollout.
- **Seed data is never a migration.** A seeder runs only when `DEMO_SEED_ENABLED=true`, under an advisory lock, with
  `ON CONFLICT DO NOTHING`.

## Consequences

- A deploy cannot start pods against a schema they do not understand.
- With expand/contract, a rename takes two releases. That is the price of deploys without downtime.

## Alternatives considered

- **Liquibase**: equivalent; Flyway's plain SQL files are easier to review.
- **Only migrating at every pod start**: works with locks, but a failed migration then fails every pod. A single Job
  fails once and blocks the rollout.
