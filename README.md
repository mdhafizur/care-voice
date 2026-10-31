# CareVoice

A small backend that turns a nurse's spoken documentation (already transcribed on the phone)
into validated, structured vital signs, and raises alerts when values are out of range.

> "Mrs. Smith, blood pressure 120 over 75, pulse 80, temperature 37 point 2 degrees"
> → `BloodPressure(120/75)`, `Pulse(80)`, `BodyTemperature(37.2 °C)`, all `CONFIRMED`

Built as a deliberately compact demo of how I structure Kotlin backend code: small enough to
explain completely in 20 minutes, but with the production concerns a real system needs.

## Architecture

![System architecture](docs/diagrams/1-architecture.svg)

- **[docs/architecture.md](docs/architecture.md)**: architecture, sequence diagrams (submit a transcript, failures
  and retries, concurrent confirmation, protocol guidance) and the Kubernetes deployment.
- **[docs/adr/](docs/adr/README.md)**: Architecture Decision Records, why each technology and pattern was chosen
  and what it costs.
- **[docs/local-access.md](docs/local-access.md)**: every local URL, demo user and default password in one place.
- **[docs/kubernetes.md](docs/kubernetes.md)**: how Kubernetes works, mapped to this repo, and how to run the local k3d cluster.
- **[docs/walkthrough.md](docs/walkthrough.md)**: guided tour: live demo in 14 steps (`make demo`), requirements and
  how they are met, decisions with their costs, open gaps with fixes, questions by perspective.
- **[docs/availability.md](docs/availability.md)**: why every service runs as several pods, why two services instead
  of a monolith, and what happens when Postgres, MongoDB, RabbitMQ or Redis is down.
- **[docs/indexing.md](docs/indexing.md)**: every index and the query it serves, benchmark numbers on 1M rows, and the
  plan tests that keep them in use.
- **[docs/CONTRACTS.md](docs/CONTRACTS.md)**: the binding specification (API, events, configuration, conventions).

| Module | Purpose |
|---|---|
| `domain` | pure Kotlin: normalize, extract, validate, review policy (~400 data-driven tests) |
| `contracts` | event and payload types shared by both services |
| `documentation-service` | Ktor + PostgreSQL: auth, transcripts, observations, outbox relay, SSE |
| `alert-service` | Ktor + MongoDB: idempotent event consumer, alert rules, alert API, protocol guidance (RAG) |
| `test-support` | test buckets, JSON assertions, OpenAPI contract checks, embedded/container databases |
| `architecture-test` | Konsist rules for the hexagonal layering |
| `load-test` | Gatling smoke, load and stress simulations |

## Where to find each concept

`doc` = `documentation-service/src/main/kotlin/com/carevoice/documentation`,
`alert` = `alert-service/src/main/kotlin/com/carevoice/alert`.

| Concept | Where |
|---|---|
| Value classes, smart constructors, "make invalid states unrepresentable" | `domain/.../model/VitalSign.kt` |
| Typed errors instead of exceptions (`Either`, sealed `DomainError`) | `domain/.../common/Either.kt`, `model/DomainError.kt` |
| Strategy + Composite pattern | `domain/.../extraction/RuleExtractors.kt`, `alert/domain/AlertRules.kt` |
| Chain of responsibility (rules first, AI fallback) | `domain/.../application/TranscriptProcessor.kt` |
| Transformation (spoken forms → canonical, °F → °C) | `TranscriptNormalizer.kt`, `TemperatureExtractor` |
| Hexagonal architecture, enforced | ports in `*/application/Ports.kt`, adapters in `*/infrastructure`, rules in `architecture-test` |
| AI output is never trusted: same validation as rules, always human review | `AiVitalExtractor.kt`, `review/ReviewPolicy.kt`, `doc/infrastructure/ai` |
| Transaction (Unit of Work) + transactional outbox | `doc/application/SubmitTranscript.kt`, `doc/infrastructure/persistence/JdbcTransactions.kt`, `doc/outbox` |
| Retry with exponential backoff and full jitter, circuit breaker | `doc/resilience/Backoff.kt`, `Retry.kt`, `CircuitBreakers.kt`, `doc/outbox/OutboxRelay.kt` |
| Backpressure: bounded channel, bounded concurrency, prefetch, semaphore | `doc/outbox/OutboxRelay.kt`, `alert/infrastructure/amqp`, `alert/api/Routes.kt` |
| RabbitMQ: publisher confirms, quorum queues, retry queue, DLQ | `doc/infrastructure/amqp/AmqpEventPublisher.kt`, `alert/infrastructure/amqp` |
| Idempotency (keys, unique indexes, never check-then-insert) | `doc/application/SubmitTranscript.kt`, `alert/application/ProcessObservationEvent.kt` |
| Hot vs cold Flows, SSE with Redis fan-out and `Last-Event-ID` replay | `doc/application/ObservationStream.kt`, `doc/api/routes/StreamRoutes.kt` |
| Caching: Caffeine cache-aside (Decorator), Redis invalidation, `ETag` / `304` | `doc/infrastructure/cache`, `doc/infrastructure/redis` |
| JWT auth with roles, bcrypt, service token, ward isolation (404 not 403) | `doc/infrastructure/auth`, `doc/api/plugins/Security.kt`, `alert/api/Security.kt` |
| AES-256-GCM field-level encryption with key rotation | `doc/infrastructure/crypto/AesGcmFieldEncryptor.kt` |
| HTTP `QUERY` method, RFC 9457 problem details, precise status codes | `doc/api/routes/ObservationRoutes.kt`, `doc/api/Problems.kt` |
| Keyset (cursor) pagination, optimistic locking | `doc/application/PageCursor.kt`, `alert/application/CursorCodec.kt`, `AcknowledgeAlert.kt` |
| Indexes and migrations (Flyway, versioned Mongo runner) | `documentation-service/src/main/resources/db/migration`, `alert/infrastructure/migrations` |
| Index design: ESR, keyset, partial indexes, extended statistics, benchmark, plan tests in CI | [docs/indexing.md](docs/indexing.md), `scripts/db-bench`, `*QueryPlanTest.kt` |
| RAG: chunking, embeddings, small-to-big retrieval, grounding check, evaluation set | `alert/domain/guidance`, `alert/application/GetAlertGuidance.kt`, `alert/infrastructure/guidance` |
| Model provider as an adapter: Claude or Gemini (Template Method, JSON schema, thinking level) | `doc/infrastructure/ai`, `alert/infrastructure/guidance/Gemini*.kt`, [ADR-0025](docs/adr/0025-model-provider-behind-ports.md) |
| Observability: MDC, OTel agent, percentiles, exemplars, dashboards | `doc/infrastructure/tracing`, `*/Dockerfile`, `deploy/grafana` |
| JVM container flags, jlink runtime, slim images | `*/Dockerfile` |
| Multi-pod rules (advisory locks, shared rate limits, fan-out) | CONTRACTS section 9b, `doc/infrastructure/redis` |
| Kubernetes: Helm chart, HPA, PDB, NetworkPolicies, operators | `deploy/helm/carevoice`, `deploy/k8s`, [docs/kubernetes.md](docs/kubernetes.md) |
| Availability: replicas, critical vs degradable dependencies, outage behaviour | [docs/availability.md](docs/availability.md) |

## Run

```bash
cp .env.example .env      # once; dev defaults
make build                # compile, all tests, format check, coverage gate (what CI runs)
make up-fast              # docker compose: both services (2 replicas each) and every dependency
make monitoring-up        # optional: Uptime Kuma with monitors and status page
make admin-up             # optional: pgAdmin and mongo-express
make k8s-up               # optional: the same system on a local Kubernetes cluster (k3d)
make security-scan        # optional: ZAP, Trivy and gitleaks reports in build/security
make db-bench             # optional: index benchmark on 1M rows, report in build/db-bench
make demo                 # optional: interactive 14-step live demo (docs/walkthrough.md)
make help                 # every target
```

Requires a JDK 21 or newer (bytecode targets Java 21) and Docker; `make k8s-up` also needs k3d, kubectl and helm.
Try the API with the Postman collection in `postman/` (import it, set `demoPassword`, run folder `01 Auth` first).

### Local URLs (docker compose)

| URL | Also on | Service |
|---|---|---|
| http://api.carevoice.127.0.0.1.nip.io | http://localhost:8080 | documentation-service (Swagger UI at `/docs`) |
| http://alerts.carevoice.127.0.0.1.nip.io | http://localhost:8081 | alert-service (alerts API, `/docs`, health) |
| http://grafana.carevoice.127.0.0.1.nip.io | http://localhost:3000 | Grafana |
| http://status.carevoice.127.0.0.1.nip.io/status/carevoice | http://localhost:3001 | Uptime Kuma status page (`make monitoring-up`) |
| http://pgadmin.carevoice.127.0.0.1.nip.io | http://localhost:5050 | pgAdmin, documentation-service database (`make admin-up`) |
| http://mongo.carevoice.127.0.0.1.nip.io | http://localhost:8082 | mongo-express, alert-service database (`make admin-up`) |

Traefik routes the names on port 80. `*.127.0.0.1.nip.io` is public DNS that resolves to 127.0.0.1, so no `/etc/hosts`
entries are needed, but it needs internet, and some home routers (e.g. FRITZ!Box DNS rebind protection) block it.
Then set `CAREVOICE_DOMAIN=carevoice.localhost` in `.env` (works in browsers and curl without DNS) and run `make up-fast`.
If port 80 is already taken on your machine, set `CAREVOICE_HTTP_PORT=8000` in `.env` and add `:8000` to the URLs.

### Local URLs (Kubernetes, `make k8s-up`)

| URL | Service |
|---|---|
| http://carevoice.127.0.0.1.nip.io:9080 (https on `:9443`, self-signed) | documentation-service (Swagger UI at `/docs`) |
| http://grafana.carevoice.127.0.0.1.nip.io:9080 | Grafana |
| `kubectl -n carevoice port-forward svc/alert-service 18081:8081` | alert-service (cluster-internal) |

Credentials, database connections and more: [docs/local-access.md](docs/local-access.md).

## Design decisions

The full set, with alternatives and trade-offs, is in [docs/adr/](docs/adr/README.md). The short version:

- **Domain has zero dependencies.** It compiles without Ktor, a database or a coroutine library
  (`suspend` is a language feature). Frameworks are details that can be swapped.
- **`Either` instead of exceptions for validation.** Invalid input is expected, not exceptional.
  Exceptions (`require`) are reserved for programming errors, e.g. a confidence outside 0..1.
- **Hand-rolled `Either` instead of Arrow.** Keeps the domain dependency-free and is small enough
  to explain. In a larger codebase I would use Arrow.
- **Rules before AI.** Regex rules are fast, free, deterministic and explainable. The LLM is only a
  fallback, and anything it produces must be confirmed by a nurse: a wrong value in a care record
  is worse than a missing one.
- **Retrieval before generation, citations before trust.** Alert guidance comes from the facility's protocols; an
  LLM may phrase it, but every step must cite a retrieved section or the protocol's own steps are shown instead.
- **Exhaustive instead of sampled tests where possible.** The blood-pressure rule is checked for
  every combination in range (about 27,000 cases, milliseconds).
