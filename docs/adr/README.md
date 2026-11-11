# Architecture Decision Records

Why CareVoice is built the way it is. `docs/CONTRACTS.md` defines *what* the system does; these records explain
*why*, what each choice costs, and what was considered instead. Format and rules: [ADR-0001](0001-record-architecture-decisions.md).

| # | Decision | Status |
|---|---|---|
| 0001 | [Record architecture decisions](0001-record-architecture-decisions.md) | Accepted |
| 0002 | [Kotlin and Ktor, without a DI framework](0002-kotlin-and-ktor.md) | Accepted |
| 0003 | [Two services with a hexagonal architecture and a pure domain module](0003-two-services-hexagonal-architecture.md) | Accepted |
| 0004 | [PostgreSQL for documentation-service](0004-postgresql-for-documentation-service.md) | Accepted |
| 0005 | [MongoDB for alert-service](0005-mongodb-for-alert-service.md) | Accepted |
| 0006 | [Transactional outbox for publishing events](0006-transactional-outbox.md) | Accepted |
| 0007 | [RabbitMQ as the event transport, HTTP as an alternative adapter](0007-rabbitmq-as-event-transport.md) | Accepted |
| 0008 | [Idempotency everywhere, at-least-once delivery](0008-idempotency-and-at-least-once-delivery.md) | Accepted |
| 0009 | [Deterministic rules first, AI only as a reviewed fallback](0009-rules-first-ai-fallback.md) | Accepted |
| 0010 | [HTTP API conventions: RFC 9457, status codes, the QUERY method](0010-http-api-conventions.md) | Accepted |
| 0011 | [Keyset pagination, optimistic locking, conditional requests](0011-keyset-pagination-and-optimistic-locking.md) | Accepted |
| 0012 | [JWT auth, ward-scoped authorization, field-level encryption](0012-authentication-authorization-encryption.md) | Accepted |
| 0013 | [Every service runs as two or more pods](0013-every-service-runs-as-multiple-pods.md) | Accepted |
| 0014 | [Redis for shared state, Caffeine as the local cache](0014-redis-for-shared-state-caffeine-as-l1.md) | Accepted |
| 0015 | [Observability and tracing without a service mesh](0015-observability-and-tracing-without-a-service-mesh.md) | Accepted |
| 0016 | [PgBouncer in Kubernetes only; no pooler for MongoDB](0016-connection-pooling-pgbouncer-and-mongodb.md) | Accepted |
| 0017 | [Health endpoints `/livez` and `/readyz`](0017-health-endpoints-livez-readyz.md) | Accepted |
| 0018 | [Tests against real database engines, embedded by default](0018-tests-against-real-database-engines.md) | Accepted |
| 0019 | [Test conventions and contract tests](0019-test-conventions-and-contract-tests.md) | Accepted |
| 0020 | [Deployment: compose, Kubernetes with Helm, Uptime Kuma](0020-deployment-kubernetes-helm-uptime-kuma.md) | Accepted |
| 0021 | [Slim multi-stage container images with a jlink runtime](0021-slim-multistage-container-images.md) | Accepted |
| 0022 | [Schema migrations and demo seeding](0022-migrations-and-seeding.md) | Accepted |
| 0023 | [Retrieval-augmented protocol guidance for alerts](0023-rag-protocol-guidance.md) | Accepted |
| 0024 | [Index design is measured and query plans are tested](0024-query-plans-are-tested.md) | Accepted |
| 0025 | [The model provider is an adapter: Claude or Gemini](0025-model-provider-behind-ports.md) | Accepted |
| 0026 | [PostgreSQL backups: continuous WAL archiving with point-in-time recovery](0026-postgres-backups-wal-archiving.md) | Accepted |
| 0027 | [Arrow's Either for typed errors in the domain](0027-arrow-for-typed-errors.md) | Accepted |

Records describe the system as it is today: when details change, the record is updated (its date stays the date of
the decision). A reversed decision gets a new record, and the old one is marked `Superseded by ADR-NNNN` (ADR-0001).

New decision: copy the structure of an existing record, take the next number, add it to this table.
