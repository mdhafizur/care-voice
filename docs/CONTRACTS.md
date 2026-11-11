# CareVoice: shared contracts

This file is the single source of truth. Every module is built against it. If the code and this file disagree, this
file wins until someone changes it on purpose. All user-facing text (API messages, logs, docs, examples) is in
**English**.

## 1. Product in one paragraph

A nurse speaks a note into a phone app. The app's on-device speech recognition sends the **transcript** to
`documentation-service`. The service finds **vital signs** in it (blood pressure, pulse, body temperature) with fixed
rules. Only when the rules find nothing does it ask an AI model. It checks every value, stores it as an
**observation**, and publishes an event through a transactional outbox. `alert-service` checks the event against
alert rules (for example systolic ≥ 180) and stores **alerts** for the ward lead. A value that the rules found with
high confidence is `CONFIRMED`. A value from the AI, or with low confidence, is `NEEDS_REVIEW` until a nurse confirms
it. A wrong value in a care record is worse than a missing one.

## 2. Modules and packages

| Module | Base package | Notes |
|---|---|---|
| `domain` | `com.carevoice.domain` | Pure Kotlin; only dependency Arrow's core library (`Either`, `either { }`, ADR-0027). Entry point: `TranscriptProcessor`. |
| `contracts` | `com.carevoice.contracts` | `ObservationRecordedEvent`, `VitalPayload`, `ContractJson`. |
| `test-support` | `com.carevoice.testsupport` | Test buckets, `assertJsonEquals`, `withField`, `OpenApiContract`, `TestDatabases`. |
| `documentation-service` | `com.carevoice.documentation` | Ktor, PostgreSQL, port 8080 |
| `alert-service` | `com.carevoice.alert` | Ktor, MongoDB, port 8081 |
| `load-test` | `com.carevoice.loadtest` | Gatling, Kotlin DSL |
| `architecture-test` | `com.carevoice.architecture` | Konsist rules across modules |

Layers inside each service (hexagonal architecture):
```
com.carevoice.<svc>
├── Application.kt          main(), wiring (manual constructor injection, no DI framework)
├── config/                 typed config (AppConfig), read from env, checked at startup (fail fast, all errors at once)
├── api/                    Ktor routes, request/response DTOs, mappers DTO <-> domain, problem details
├── application/            use cases (one class per use case) and the ports (interfaces) they depend on
├── infrastructure/         adapters: persistence (JDBC / Mongo), HTTP clients, crypto, cache
└── observability/          logging setup, metric names, health checks
```
documentation-service also has `outbox/` (the outbox relay) and `resilience/` (backoff, retry, circuit breakers).

Rules (checked by `architecture-test`):
- `domain` imports nothing from `io.ktor`, `java.sql` or `com.mongodb`.
- `api` never uses `infrastructure` directly. It goes through `application`.
- Classes in `application` depend on ports, not on adapters.

## 3. Build

- Convention plugins (in `build-logic`): `carevoice.kotlin-library` (every module), `carevoice.kotlin-service`
  (runnable services: adds `application`, kotlinx-serialization and the CycloneDX SBOM) and
  `carevoice.kotlin-serialization`. Apply them by id, without a version.
- Library versions live only in `gradle/libs.versions.toml`. Add new libraries there. Never write a version inline.
- Bytecode target: Java 21. Runtime image: multi-stage build with a jlink-minimised JRE on `debian:bookworm-slim`
  (section 14).
- `./gradlew build` compiles, runs the tests, `spotlessCheck` and `koverVerify` (at least 80 % of lines and 65 % of
  branches covered).
- Services are packaged with `installDist`. The Dockerfile copies `build/install/<service>`.

## 4. Configuration (environment variables)

Each service reads a typed `AppConfig` from the environment at startup. If anything is missing or invalid, it **stops
and lists every problem at once**, not only the first one.

| Variable | Service | Default | Notes |
|---|---|---|---|
| `HTTP_PORT` | both | 8080 / 8081 | 1 to 65535 |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | documentation | — | required; JDBC URL `jdbc:postgresql://host:port/database` |
| `DATABASE_POOL_SIZE` | documentation | 10 | Hikari maximum pool size, 1 to 200 |
| `DATABASE_MIGRATION_URL` | documentation | = `DATABASE_URL` | direct Postgres connection for Flyway. Never through PgBouncer: Flyway holds a session-level advisory lock |
| `DATABASE_MIGRATE_ON_STARTUP` | documentation | `true` | `false`: only check the schema, and refuse to start if a migration is pending or changed (section 14) |
| `DATABASE_PREPARE_THRESHOLD` | documentation | driver default (5) | set `0` behind PgBouncer < 1.21 in transaction mode, to turn off server-side prepared statements |
| `DATABASE_READ_URL`, `DATABASE_READ_USER`, `DATABASE_READ_PASSWORD` | documentation | — (primary credentials) | optional read replica, used only by the observation query (section 14) |
| `MONGO_URI` | alert | — | required; must start with `mongodb://` or `mongodb+srv://` |
| `MONGO_DATABASE` | alert | `carevoice_alerts` | `[A-Za-z0-9_-]{1,63}` |
| `MONGO_POOL_SIZE` | alert | 20 | 1 to 500 |
| `MONGO_MIGRATE_ON_STARTUP` | alert | `true` | `false`: only check the schema version (section 14) |
| `JWT_SECRET` | both | — | required; at least 32 bytes; HS256 shared secret |
| `JWT_ISSUER` / `JWT_AUDIENCE` | both | `carevoice-auth` / `carevoice` | |
| `JWT_TTL_SECONDS` | documentation | 900 | access token lifetime, 60 to 86400 |
| `DATA_ENCRYPTION_KEY` | documentation | — | required; base64 of exactly 32 random bytes |
| `DATA_ENCRYPTION_KEY_ID` | documentation | `v1` | prefix of every ciphertext; makes key rotation possible |
| `DATA_ENCRYPTION_PREVIOUS_KEYS` | documentation | — | old keys after a rotation, `id:base64,id:base64`; still readable, never used for writing |
| `EVENT_TRANSPORT` | documentation | `amqp` | `amqp` (RabbitMQ) or `http` (direct POST to alert-service) |
| `ALERT_SERVICE_URL` | documentation | — | e.g. `http://alert-service:8081`; required when `EVENT_TRANSPORT=http` |
| `RABBITMQ_URL` | both | — | `amqp://user:pass@rabbitmq:5672/carevoice`; documentation-service needs it when the transport is `amqp`, alert-service always (it always consumes) |
| `RABBITMQ_PREFETCH` | alert | 32 | unacked messages per consumer, 1 to 1000; this is the broker's backpressure |
| `REDIS_URL` | documentation | — | required; e.g. `redis://redis:6379` (also `rediss://`, `redis-sentinel://`); shared rate limits, cache invalidation, SSE fan-out |
| `AI_FALLBACK_ENABLED` | documentation | `false` | ask the AI model when the rules find nothing |
| `AI_PROVIDER` | both | `claude` | `claude` or `gemini`: which model API the AI fallback and the guidance writer call (ADR-0025) |
| `ANTHROPIC_API_KEY` | both | — | required only when an enabled AI feature uses Claude |
| `AI_MODEL` | both | `claude-haiku-5-5` | Claude model; never sent to Gemini |
| `GEMINI_API_KEY` | both | — | required only when an enabled AI feature uses Gemini; sent in the `x-goog-api-key` header, never in the URL |
| `GEMINI_MODEL` | both | `gemini-flash-latest` | Gemini model for extraction and guidance |
| `GEMINI_THINKING_LEVEL` | both | `low` | `minimal`, `low`, `medium` or `high`; `none` sends no thinking setting (for Gemini 2.5) |
| `AI_TIMEOUT_MS` | documentation | `3000` (Claude), `5000` (Gemini) | time limit for one AI attempt, 500 to 30000; on timeout the rule result stands |
| `TRANSCRIPT_RETENTION_DAYS` | documentation | 30 | raw transcript text is erased after this many days, 1 to 3650 |
| `DEMO_SEED_ENABLED`, `DEMO_PASSWORD` | documentation | `false`, — | creates the demo tenant, wards, residents and users; the password must have at least 8 characters |
| `RATE_LIMIT_PER_MINUTE` | both | 120 | per signed-in user, 1 to 100000 (the login limit is fixed: 10 per client IP per minute) |
| `TRUSTED_PROXIES` | documentation | empty | comma-separated CIDR networks whose `X-Forwarded-For` is believed (Traefik / the ingress), for the per-IP login limit; empty = the header is ignored. Invalid entries fail the start |
| `REQUEST_TIMEOUT_MS` | both | `30000` | time limit for one HTTP request, 1000 to 120000. After it, the request is cancelled and answered with **503** + `Retry-After: 2`. Keep it above the AI fallback budget (2 × `AI_TIMEOUT_MS`). The SSE stream has no limit |
| `GUIDANCE_EMBEDDER` | alert | `hashing` | `hashing` (local, deterministic), `voyage` or `gemini` (semantic); section 9c |
| `VOYAGE_API_KEY`, `VOYAGE_MODEL` | alert | —, `voyage-3.5-lite` | key required only when `GUIDANCE_EMBEDDER=voyage` |
| `GEMINI_EMBEDDING_MODEL` | alert | `gemini-embedding-001` | used when `GUIDANCE_EMBEDDER=gemini` (768 dimensions, normalized) |
| `GUIDANCE_AI_ENABLED` | alert | `false` | an LLM writes the guidance steps; `AI_PROVIDER` picks Claude or Gemini (needs that provider's key) |
| `LOG_LEVEL` (or `CAREVOICE_LOG_LEVEL`) | both | `INFO` (`DEBUG` in docker compose) | level of the application loggers (`com.carevoice.*`); libraries stay at INFO/WARN; never DEBUG in production |

docker compose reads a few more variables from `.env`. They configure the stack, not the services:
`CAREVOICE_DOMAIN` (host names, default `carevoice.127.0.0.1.nip.io`), `CAREVOICE_HTTP_PORT` (port for host-name
routing, default 80), `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB`, `RABBITMQ_USER` / `RABBITMQ_PASSWORD` /
`RABBITMQ_VHOST`, `POSTGRES_LOG_STATEMENT` (default `none`; `mod` logs every write), `KUMA_USERNAME` /
`KUMA_PASSWORD`, `PGADMIN_PASSWORD`, `MONGO_EXPRESS_PASSWORD`.

## 5. Authentication and authorization

- `POST /v1/auth/token` (documentation-service) exchanges a username and password for a JWT. Passwords are stored
  with bcrypt (cost 12). A wrong username and a wrong password give the **same** 401 and take the same time, so nobody
  can find out which usernames exist.
- JWT HS256. Claims: `iss`, `aud`, `sub` (username), `tenant`, `ward`, `roles` (array), `iat`, `exp`, `jti`.
- Roles:
  - `NURSE`: document, read the own ward, confirm values.
  - `WARD_LEAD`: everything a nurse can do, plus alerts.
  - `SERVICE`: only for service-to-service calls (documentation-service → alert-service). documentation-service
    creates this token itself with `sub` = `documentation-service`, a 5-minute lifetime, cached and refreshed before
    it expires.
- **Tenant and ward isolation**: every query is limited to the caller's `tenant` and `ward` claims. A resource in
  another ward gets **404, not 403**, so the API does not reveal that it exists. Checks run in this order:
  authenticate (401) → role (403) → does it exist in the caller's scope (404) → business rules.
- Demo users (created only when `DEMO_SEED_ENABLED=true`; password from `DEMO_PASSWORD`, `admin123` in the local
  `.env`): `nurse.anna` (ward-a, NURSE), `nurse.ben` (ward-b, NURSE), `lead.clara` (ward-a, WARD_LEAD). Tenant
  `sunrise-care`; wards `ward-a` "Maple Ward" and `ward-b` "Oak Ward"; residents `res-1`…`res-6` (three per ward).

## 6. HTTP conventions (both services)

- Base path `/v1`. The version is in the URL; a breaking change gets `/v2`.
- JSON only. A body with the wrong `Content-Type` → **415**. A body larger than 64 KiB → **413**.
- Errors follow **RFC 9457** (`application/problem+json`):
  `{"type":"https://carevoice.dev/problems/<slug>","title":"...","status":400,"detail":"...","instance":"/v1/...","requestId":"..."}`.
  Validation errors add `"errors":[{"field":"transcript","message":"must not be blank"}]`.
  5xx bodies never contain exception messages or stack traces: `"detail":"An unexpected error occurred."`.
- Status codes in use: 200, 201 (+`Location`), 202, 204, 304, 400 (malformed JSON, wrong types, missing headers),
  401 (+`WWW-Authenticate: Bearer`), 403, 404, 409, 412, 413, 415, 422 (well-formed but not valid), 428,
  429 (+`Retry-After`), 500, 503 (+`Retry-After`). Every `/v1` endpoint can answer 500 and 503.
- Every request has an `X-Request-Id`. The service takes it from the request when it matches `[A-Za-z0-9._:-]{1,64}`,
  otherwise it creates one. It is sent back in the response and put in the log MDC as `requestId`.
- Lists use keyset pagination: `?limit=` (1–100, default 20) and an opaque `?cursor=`. Responses carry
  `X-Total-Count` and an RFC 8288 `Link` header with `first`, `next` (when there are more items) and `prev` (when not
  on the first page). An unknown but well-formed cursor → first page. A malformed cursor → 400.
- Concurrency: resources that can change have `ETag: "<version>"`. An update needs `If-Match`. Missing → **428**,
  outdated → **412**.
- Conditional GET: lists and single resources carry a weak ETag of the body. If `If-None-Match` matches → **304**.
- Each service has an OpenAPI 3.1 spec at `src/main/resources/openapi/<service>.yaml`, served at `/openapi.yaml`, with
  Swagger UI at `/docs`. **Contract first**: change the spec, then the code. Every integration test checks responses
  against the spec with `OpenApiContract`. Every status code a handler can send must be in the spec.
- Health: `GET /livez` (the process is up) and `GET /readyz` (the required dependencies are reachable: Postgres for
  documentation-service; MongoDB and the RabbitMQ consumer for alert-service; 503 when not). Metrics: `GET /metrics`
  (Prometheus format). Health, metrics and docs need no login.
- Time limits, so a cancelled or abandoned request never keeps working and nothing waits forever:
  - Request deadline `REQUEST_TIMEOUT_MS` (default 30 s; the SSE stream is exempt). When it passes, the handler
    coroutine is cancelled and the client gets **503** + `Retry-After: 2`. This is logged once as WARN
    `Request deadline exceeded` (method, path, timeoutMs). A client that disconnects cancels the handler the same way.
    Cancellation is cooperative: suspending calls stop at once; a blocking JDBC statement finishes first (it is limited
    by the next point).
  - Postgres: migration `V4` sets limits on the application role, which also works behind PgBouncer in transaction
    mode: `statement_timeout = 5s` and `idle_in_transaction_session_timeout = 15s`. A cancelled statement (SQLState
    `57014`) or an ended idle transaction (`25P03`) becomes **503**. The transaction rolls back, so nothing is half
    saved. A migration that needs longer runs `SET LOCAL statement_timeout = 0` in its own transaction.
  - PgJDBC `socketTimeout = 30s`: the last safety net when the server never answers (for example a network split).
  - A read outside a transaction whose connection was lost (SQLState class 08, or 57P01–57P03) is retried once on a
    fresh connection after 100–300 ms (WARN `Read failed on a lost connection; retrying once`). Never retried:
    writes (the commit may already have happened), anything inside a transaction, statement timeouts, and "no
    pooled connection in time".
  - When the database is unavailable, documentation-service answers 503 + `Retry-After: 2`; alert-service answers
    503 + `Retry-After: 1`.
  - alert-service guidance providers (LLM writer, remote embedder) have their own circuit breakers
    (`guidance-writer`, `guidance-embedder`): they open when 50 % of the last 10 calls failed (at least 5 calls) and
    stay open for 30 s. While open, guidance falls back to the extractive steps at once, and a failing embedder
    answers 503 at once.
  - MongoDB: 10 s socket read timeout and a 5 s write-concern timeout (`wtimeout`). The migration lock is released
    under `NonCancellable`, so a shutdown during a migration never leaves the lock behind.
- Graceful shutdown (documentation-service): readiness answers 503 and the pod waits 3 s so the load balancer
  notices → SSE streams are closed (clients reconnect elsewhere) → the server stops accepting and finishes in-flight
  requests (10 s) → the outbox relay stops after its current batch → pools and clients are closed.

## 7. documentation-service API (port 8080)

| Method + path | Role | Success | Errors (plus 500 and 503) |
|---|---|---|---|
| `POST /v1/auth/token` body `{"username","password"}` | none | 200 `{"accessToken","tokenType":"Bearer","expiresIn"}` | 400, 401, 413, 415, 429 |
| `GET /v1/residents/{residentId}` | NURSE, WARD_LEAD | 200 `{"id","wardId","displayName"}` (decrypted, Caffeine cache-aside), ETag/304 | 401, 403, 404, 429 |
| `POST /v1/residents/{residentId}/transcripts` header `Idempotency-Key: <uuid>` body `{"transcript":"..."}` | NURSE, WARD_LEAD | 201 + `Location: /v1/transcripts/{id}` | 400 (missing or invalid key), 401, 403, 404, 409 (key reused with a different body), 413, 415, 422 (blank, > 2000 chars), 429 |
| the same request again, same key + same body | | 200, identical body, header `Idempotent-Replayed: true` | |
| `GET /v1/transcripts/{transcriptId}` | NURSE, WARD_LEAD | 200, same body as the 201 | 401, 403, 404, 429 |
| `GET /v1/residents/{residentId}/observations?limit&cursor` | NURSE, WARD_LEAD | 200 list, Link, X-Total-Count, ETag/304 | 400, 401, 403, 404, 429 |
| `QUERY /v1/observations` body = filter | NURSE, WARD_LEAD | 200 list, ETag/304 (cached 10 s per ward + filter; cleared when that ward gets a new observation) | 400, 401, 403, 413, 415, 422, 429 |
| `POST /v1/observations/search` body = filter | NURSE, WARD_LEAD | same as QUERY (for clients and proxies that do not support QUERY) | same as QUERY |
| `PATCH /v1/observations/{observationId}/confirm` header `If-Match` | NURSE, WARD_LEAD | 200 updated observation + new ETag | 401, 403, 404, 409 (already confirmed), 412, 428, 429 |
| `GET /v1/observations/stream` (SSE) | NURSE, WARD_LEAD | `text/event-stream`, event `observation` with the observation JSON, heartbeat comment every 15 s, only the caller's ward | 401, 403, 429 |

Transcript response body (201/200):
```json
{
  "transcriptId": "uuid",
  "residentId": "res-1",
  "observations": [
    {"id":"uuid","residentId":"res-1","vital":{"type":"BLOOD_PRESSURE","systolic":120,"diastolic":75},
     "source":"RULES","confidence":0.95,"status":"CONFIRMED","recordedAt":"2026-10-25T10:00:00Z",
     "confirmedBy":null,"confirmedAt":null,"version":0}
  ],
  "rejected": [{"matchedText":"bp 12/10","reason":"systolic=12 is outside the plausible range [50, 260]"}],
  "usedAiFallback": false
}
```
Observation filter (QUERY / search): `{"residentIds":["res-1"]?, "vitalTypes":["PULSE"]?, "status":"NEEDS_REVIEW"?,
"recordedFrom":"instant"?, "recordedTo":"instant"?, "limit":20?, "cursor":"..."?}`. Unknown fields → 400.
`recordedFrom` after `recordedTo` → 422. List body: `{"items":[observation...],"nextCursor":"..."|null}`.

Processing a transcript is **one database transaction**: insert the transcript (with the encrypted text and the
response body), insert the observations, insert one outbox row per observation, insert one audit row. Only after
the commit: publish to the live feed (SSE) and clear the ward's query cache.

## 8. Outbox relay (inside documentation-service, package `com.carevoice.documentation.outbox`)

- `Application.kt` starts it with `OutboxRelay.start(scope: CoroutineScope)` and stops it with `suspend fun stop()`.
- Loop: in one short transaction, claim up to 50 due rows (`status = 'PENDING'` and `next_attempt_at` ≤ now, oldest
  first, `FOR UPDATE SKIP LOCKED`) and move their `next_attempt_at` forward by a 30 s lease. If this instance crashes,
  another one picks the rows up after the lease. Publishing happens outside the transaction.
- **Backpressure**: claimed rows go into a bounded `Channel(capacity = 100)`. 8 publisher coroutines read from it.
  When the channel is full, the claimer waits instead of claiming more.
- **Retry with exponential backoff and full jitter**: `delay = random(0, min(cap, base * 2^attempt))`, base 500 ms,
  cap 60 s. A longer `Retry-After` from the consumer wins. A row becomes `DEAD` only when it has used 10 attempts AND
  is older than 24 h (`retryFor`). So an outage of up to a day never makes valid events `DEAD`, even on a quiet ward
  whose few calls never open the circuit breaker.
  Retryable: timeouts, connection errors, 5xx, 429. Not retryable: any other 4xx → `DEAD` at once, with `last_error`.
- Circuit breakers (Resilience4j) around each publisher (`rabbitmq`, `alert-service`) and around each AI provider
  (`anthropic`, `gemini`): they open when 50 % of the last 20 calls failed (at least 20 calls), stay open 30 s, then
  let 3 test calls through. While the publisher's breaker is open, rows are rescheduled without counting an attempt.
- HTTP transport: POST to `${ALERT_SERVICE_URL}/v1/events/observations` with a `SERVICE` JWT and the header
  `Idempotency-Key: <eventId>`, time limit 2 s per call.
- Published rows stay in the table with `status = 'PUBLISHED'`. Nothing deletes them yet.
- Metrics: `carevoice_outbox_pending` (gauge), `carevoice_outbox_publish_total{result=published|retry|dead|circuit_open}`,
  `carevoice_outbox_publish_seconds` (timer), `carevoice_outbox_lag_seconds` (age of the oldest pending row).

## 8a. RabbitMQ transport (default event transport)

The outbox relay publishes through the `EventPublisher` port. `EVENT_TRANSPORT` picks the adapter:
`AmqpEventPublisher` (default) or `AlertServiceClient` (HTTP, section 8). Both send the same
`ObservationRecordedEvent` JSON, and alert-service handles both with the same use case. Delivery is at-least-once
and idempotent on `eventId` either way.

Topology. The apps declare it at startup, and declaring twice is harmless: the publisher declares the exchanges, the
consumer declares its queues and bindings.

| Name | Type | Notes |
|---|---|---|
| `carevoice.events` | topic exchange, durable | all domain events |
| `carevoice.events.retry` | direct exchange, durable | delayed retries |
| `carevoice.events.dlx` | direct exchange, durable | dead letters |
| `alert-service.observations` | **quorum** queue | bound to `carevoice.events` with `observation.recorded.*` and `retry.alert-service.observations`; args `x-queue-type=quorum`, `x-delivery-limit=10`, `x-dead-letter-exchange=carevoice.events.retry`, `x-dead-letter-routing-key=alert-service.observations` |
| `alert-service.observations.retry` | quorum queue | first retry tier (5 s): bound to the retry exchange with `alert-service.observations`; `x-message-ttl=5000`; when the time is up it dead-letters back to `carevoice.events` with routing key `retry.alert-service.observations`, which only `alert-service.observations` is bound to. So a retry goes back to this consumer only, not to every subscriber of the topic. The main queue's own dead-lettering also lands here |
| `alert-service.observations.retry.{30s,120s,600s,1800s}` | quorum queues | the other retry tiers, bound with `alert-service.observations.retry.<delay>`; same arguments, each with its own TTL. One queue per delay, because a queue only expires the message at its head |
| `alert-service.observations.dlq` | quorum queue | bound to `carevoice.events.dlx` with `alert-service.observations`; the parking lot; alert rule `AlertEventsDeadLettered` in Kubernetes |

Messages: routing key `<eventType>.v<schemaVersion>` (`observation.recorded.v1`), `content_type=application/json`,
`delivery_mode=2` (persistent), `message_id=eventId`, `type=observation.recorded`, `timestamp` = when the event
happened, headers `schemaVersion`, `requestId`, `traceparent`.
- **Publisher**: publisher confirms are on. An outbox row becomes `PUBLISHED` only after the broker confirms it. A
  nack, no answer within 5 s, or an unroutable message returned by the broker (`mandatory=true`) → `Retryable`.
  Broker unreachable → `Retryable` too: the outbox simply keeps the events, and nurses can keep documenting while the
  broker is down. Channels are not thread-safe, so each publish borrows a channel from a pool.
- **Consumer**: manual acks, and `basicQos(RABBITMQ_PREFETCH)` as backpressure. It acks only after the Mongo writes
  succeeded. A duplicate `eventId` → ack (already processed).
  - Short failure: the consumer publishes the message to the retry tier for its attempt (5 s, 30 s, 2 min, 10 min,
    30 min) with header `x-carevoice-attempt` = number of the next attempt. It waits for the broker's confirm, then
    acks the original. If that publish fails, `basicNack(requeue=false)` sends the message to the 5 s tier.
  - When delivery 6 fails (about 43 minutes after the first), the consumer publishes the message to
    `carevoice.events.dlx` and acks it. Invalid JSON or an unknown `schemaVersion` → straight to the DLX.
  - **MongoDB down** (a write failed AND the Mongo health check confirms the outage): the consumer pauses instead. It
    cancels its subscription, puts in-flight messages back in the queue without counting an attempt
    (`basicNack(requeue=true)`), checks MongoDB every 5 s, and subscribes again once it is healthy.
    `carevoice_amqp_consumer_paused` is 1 meanwhile. A long outage leaves the events waiting in the queue instead of
    using up their retries.
  - **Replay**: `alert-service replay-dead-letters [limit]` (`make dlq-replay LIMIT=n`) moves dead-lettered messages
    back to `carevoice.events` with routing key `retry.alert-service.observations` (this queue only). It resets the
    retry state and increments `x-carevoice-replayed`. It moves at most the messages that were there when it started,
    and acks each one only after the broker confirmed the copy.
  - Automatic connection recovery is on. Graceful shutdown cancels the consumer, waits for in-flight messages, then
    closes.
- Metrics: `carevoice_amqp_publish_total{result}`,
  `carevoice_amqp_consume_total{result=processed|duplicate|retried|requeued|dead_lettered}`,
  `carevoice_amqp_consumer_paused`, `carevoice_amqp_processing_seconds` (histogram). Broker metrics come from
  RabbitMQ's Prometheus plugin (queue depth, unacked, DLQ size, consumer count).
- Readiness: documentation-service stays ready when the broker is down (the outbox keeps the events;
  `rabbitmq: DEGRADED` in the body). alert-service is not ready while its consumer is disconnected. A consumer that
  paused on purpose still counts as ready.
- Contract test: a Pact **message** pact (consumer documentation-service, provider alert-service, description
  "an observation recorded event message"), in addition to the HTTP pact.
- High availability: in Kubernetes a 3-node RabbitMQ cluster; quorum queues copy every message to the nodes (Raft).
  docker compose runs a single broker.

## 9. alert-service API (port 8081)

| Method + path | Role | Success | Errors (plus 500 and 503) |
|---|---|---|---|
| `POST /v1/events/observations` body `ObservationRecordedEvent` (`ContractJson`) | SERVICE | 202 `{"eventId","alertsCreated":n}` on the first delivery; 200 with the same body for a duplicate `eventId` | 400, 401, 403, 413, 415, 422 (unknown schemaVersion or invalid values); 503 + `Retry-After: 1` when overloaded |
| `GET /v1/alerts?status=OPEN|ACKNOWLEDGED&limit&cursor` | WARD_LEAD | 200 list of the caller's ward, Link, X-Total-Count, ETag/304 | 400, 401, 403, 429 |
| `GET /v1/alerts/{alertId}` | WARD_LEAD | 200 alert + ETag, 304 | 401, 403, 404, 429 |
| `PATCH /v1/alerts/{alertId}/acknowledge` header `If-Match` | WARD_LEAD | 200 updated alert + new ETag | 401, 403, 404, 409 (already acknowledged), 412, 428, 429 |
| `GET /v1/alerts/{alertId}/guidance` | WARD_LEAD | 200 protocol steps with citations (section 9c) | 401, 403, 404, 429; 503 when the embedding provider is down |

`/v1/events/observations` is internal: in docker compose and Kubernetes only documentation-service can reach it.

Alert: `{"id","tenantId","wardId","residentId","observationId","eventId","rule","severity":"CRITICAL|WARNING",
"message","vital":{...},"status":"OPEN|ACKNOWLEDGED","observedAt","createdAt","acknowledgedBy","acknowledgedAt","version"}`.

Rules (Strategy pattern: one class each, all thresholds in one place, `AlertThresholds`):
| Rule | Condition | Severity |
|---|---|---|
| `HYPERTENSIVE_CRISIS` | systolic ≥ 180 or diastolic ≥ 120 | CRITICAL |
| `HYPOTENSION` | systolic < 90 | WARNING |
| `TACHYCARDIA` | pulse > 120 | WARNING |
| `BRADYCARDIA` | pulse < 45 | WARNING |
| `HIGH_FEVER` | temperature ≥ 39.5 °C | CRITICAL |
| `HYPOTHERMIA` | temperature < 35.0 °C | CRITICAL |

`NEEDS_REVIEW` observations raise alerts too. The message marks them as "unconfirmed value".

Mongo collections:
- `alerts`, with indexes: unique `{eventId, rule}`; `{tenantId, wardId, status, createdAt:-1, _id:-1}`;
  `{tenantId, wardId, createdAt:-1, _id:-1}` (lists without a status filter).
- `processed_events`: unique `eventId`, TTL index of 30 days on `processedAt`.
- Duplicates are detected by the unique index (insert, catch the duplicate-key error). Never check first and insert
  after.

**Backpressure**: a `Semaphore(64)` guards event processing. If `tryAcquire` fails, the answer is 503 +
`Retry-After: 1` at once. The relay treats that as retryable.

## 9a. Redis (documentation-service)

Redis makes per-instance state work across replicas. Caffeine stays the fast local cache (L1). Redis never stores
personal data or decrypted values.
- **Rate limiting**: counters live in Redis, so the limit holds however many replicas serve the user. One Lua script
  does `INCR` + `PEXPIRE` atomically: a fixed window per user or per IP. Keys `carevoice:ratelimit:<scope>:<key>`,
  with a TTL.
- **Cache invalidation**: after the commit, a replica publishes `{"cache":"observation-query","tenant":..,"ward":..}`
  or `{"cache":"resident","id":..}` on the channel `carevoice:cache-invalidation`. Every replica (also the sender)
  clears its local Caffeine entries when the message arrives.
- **SSE fan-out**: after the commit, the observation JSON is published on the channel `carevoice:observations`. Each
  replica's subscriber feeds its local `SharedFlow`, which serves only that replica's connected clients (filtered by
  tenant and ward). The sending replica does NOT also emit locally: one path only, so no duplicates.
- **When Redis is down**: rate limiting fails open, with a WARN log and the metric
  `carevoice_ratelimit_fail_open_total` (nurses being able to document matters more than strict limits; login keeps
  an in-memory per-IP limit as a floor). Cache invalidation falls back to the short cache TTLs. SSE falls back to
  local emission. Readiness stays UP (Redis is not on the critical path), but `/readyz` reports `redis: DEGRADED` in
  its body. Metric: `carevoice_redis_up`.
- alert-service keeps its in-memory rate limit and semaphore. Its only callers are documentation-service and ward
  leads, and its backpressure is meant to be per instance (it protects that instance's resources).

## 9b. Multi-instance rules (every service runs as 2+ pods)

No code may assume it is the only instance:

| Concern | Rule |
|---|---|
| Scheduled jobs (transcript retention) | run only while holding a Postgres advisory lock (`pg_try_advisory_xact_lock`): one pod does the work, the others skip that run |
| Startup tasks (demo seeder) | advisory lock + `INSERT ... ON CONFLICT DO NOTHING`, safe when pods start at the same time |
| Migrations | Flyway's own lock (Postgres) and the Mongo migration lock; in Kubernetes a single pre-upgrade Job |
| Idempotency race | two pods get the same `Idempotency-Key` at once: the unique constraint decides; the loser catches the violation and replays the winner's stored response (or 409 if the body differs) |
| Outbox relay | every pod runs one; `FOR UPDATE SKIP LOCKED` + the lease make sure no row is published twice at the same time |
| RabbitMQ consumers | competing consumers on one quorum queue; no assumption about the order of events (each is independent and idempotent) |
| Concurrent updates | optimistic locking (`version`), never "read, then write" without it |
| Caches | Caffeine per pod + Redis invalidation; no cache is a source of truth |
| Rate limits | Redis counters (shared); alert-service backpressure is per pod on purpose |
| SSE | Redis fan-out to every pod. Events carry an `id`. A client that reconnects with `Last-Event-ID` (for example after its pod was drained) gets the missed observations of its ward replayed from Postgres, so no sticky sessions are needed |
| Shutdown | on SIGTERM: readiness answers 503 first (the load balancer stops routing), close SSE (clients reconnect elsewhere), finish in-flight requests, stop the relay and the consumer after their current work, close pools |
| Metrics | gauges about shared state (outbox pending and lag) show the same value on every pod: dashboards use `max()`; counters use `sum()` |
| Tests | every rule above has a two-instance test where practical (two modules on one Postgres, Redis or broker) |

## 9c. Protocol guidance (RAG, alert-service, ADR-0023)

- Knowledge base: markdown protocols in `alert-service/src/main/resources/protocols` (listed in `index.txt`). Each
  `##` section is one chunk, with the citation id `<documentId>#<n>`. They are loaded at startup. Loading is
  idempotent per corpus version (a hash of the embedding model and the chunk texts). They are stored in the Mongo
  collections `protocol_chunks` and `protocol_corpora` (migration M003). If loading fails, it is logged and startup
  continues.
- Retrieval: one query per aspect of the alert (clinical; plus review when the message contains "unconfirmed
  value"). The best protocol is the one with the best chunk (cosine ≥ the embedder's `minScore`). Then all sections of
  that protocol are used (small-to-big). The review protocol comes first. Queries never contain resident data.
- Writing the steps: with `GUIDANCE_AI_ENABLED=true`, the `AI_PROVIDER` LLM gets the shared prompt (excerpts only,
  citations required). Claude: temperature 0, answers `NO_GUIDANCE` to decline. Gemini: JSON schema in which citation
  ids can only be the excerpts sent (`enum`), at most 6 steps, `{"steps": []}` to decline, thinking at
  `GEMINI_THINKING_LEVEL`. Otherwise extractive: the bullets of "Immediate steps" and "When to escalate".
- Grounding check: every step must cite at least one retrieved section id and nothing else; at most 8 steps of at most
  400 characters. Otherwise the answer falls back to extractive. Response: `{alertId, rule, status:
  FOUND|NO_MATCHING_PROTOCOL, generatedBy: CLAUDE|GEMINI|EXTRACTIVE|null, steps[{text, citations[]}], sources[{id,
  protocol, section, score, excerpt}], disclaimer}`.
- Advisory only: the endpoint writes nothing. A retrieval evaluation set runs in every build
  (`ProtocolRetrievalEvaluationTest`).

## 10. Observability

- Logs: JSON to stdout (logstash-logback-encoder). Fields: `timestamp`, `level`, `logger`, `message`, `thread`,
  `requestId`, `traceId`, `spanId`, `userId` (username, when signed in), `type` (`request` | `dboperation` |
  `externalrequest` | `business`). The OTLP copy in Grafana has the same fields: the agent adds `thread.name` /
  `thread.id` (`OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_LOG_ATTRIBUTES=true`) and the collector shows the
  name as `thread`. `make up-debug` adds the coroutine name to it (`-Dkotlinx.coroutines.debug=on`). **Never** log transcript text, resident names, passwords, tokens or full bodies.
  4xx and validation failures are `INFO` (a rate-limit hit is `WARN`), 5xx is `ERROR`. ERROR lines go to stderr, all
  others to stdout, so the platform can tell errors apart. `LOG_LEVEL` sets the application level
  (`CAREVOICE_LOG_LEVEL` still works). One access-log line per request (method, path template, status, duration ms).
- Structured fields: values go into SLF4J key-value pairs, not into the message text (`log.event(level, type,
  message, "result" to ..., "durationMs" to ...)`, `StructuredLog.kt` in both services). So they are top-level JSON
  fields on stdout and attributes in Loki: `{service_name="alert-service"} | result="error"`. A rejected request logs
  `message="Request rejected"` with `status`, a `reason` slug and `path` (never a parser message, which can echo the
  body). A 503 also names the underlying `cause` class. A refused bearer token logs `Authentication failed` with
  `reason=missing-token|invalid-token` (never the token).
- Business events (`type=business`, one line each, ids only):
  - `Login succeeded` and `Login failed` (both INFO; `reason=malformed-credentials|unknown-user|wrong-password`). The
    submitted name is logged only for a known user, because an unknown "username" is often a mistyped password.
  - `Transcript processed`, `Observation confirmed` (`observationId`, `version`).
  - `Alert raised` (`alertId`, `rule`, `severity`, `observationId`; not on a redelivery).
  - `Alert acknowledged` (`alertId`, `rule`, `openForMs`: time from the alert to the acknowledgement).
  - `Guidance served` (`status`, `generatedBy`, `steps`, `protocols`).
- DEBUG (`LOG_LEVEL=DEBUG`, the docker compose default) explains why each decision was made, still without values or
  text:
  - Both services: `Value extracted` (`vitalType`, `source`, `confidence`, `status`), `Value rejected` (`reason` =
    validation error type), `No value found`, `Idempotent replay`, `AI attempt finished` (per HTTP attempt:
    `attempt`, `httpStatus`, `apiError`, `durationMs`, `timeoutMs`), `Token refused: claim missing` (`claim`),
    `Alert rules evaluated` (`rulesFired` or `none`), `Event already processed`, `Protocol retrieved` (per aspect:
    `protocol`, `topScore`, `minScore`, `sections`).
  - documentation-service: `Token issued` (`userId`, `tokenId` = jti, `roles`, `expiresAt`), `Service token
    created|refreshed`, `Rate limit checked` (`scope`, `store` = redis|memory-floor|fail-open, `allowed`,
    `remaining`, `resetInMs`/`retryAfterMs`; never the key, so never the username or IP), `Resident cache
    hit|miss|invalidated`, `Observation query cache hit|miss|invalidated` (`wardId`), `Live feed subscribed`
    (`lastEventIdSent`, `replayed`, `subscribers`) and `Live feed closed` (`delivered`), `Cache invalidation
    published|received`, `Observation published|received for live feeds` (`observationId`, `wardId`), `Outbox event
    publishing` (`attempt`, `leaseRemainingMs`), `Outbox retry scheduled` (`delayMs`, `retryAfterHonoured`), `Outbox
    row parked: circuit open`, `Outbox row released on shutdown`, `RabbitMQ publish confirmed` (`exchange`,
    `routingKey`, `messageId`, `result`, `durationMs`), `alert-service event POST finished` (`httpStatus`,
    `durationMs`), `Dependency checked` (`status`, `durationMs`, `error`), `Read served by the primary: replica
    unavailable`, `Transcript retention batch erased`, `Resident not visible` / `Transcript not found` (`reason`; the
    response stays one 404), `Resident observations listed` / `Observations queried` (filter NAMES only, `limit`,
    `cursor`, `returned`), `Confirm refused: stale version|already confirmed`, `Confirm lost a race`.
  - alert-service: `Delivery received` (`deliveryTag`, `redelivered`, `attempt`, `schemaVersion`, `bytes`),
    `Delivery acked`, `Delivery settled for retry` (`decision` = retry|retry-fallback, `attempt`, `retryQueue`),
    `Delivery requeued (consumer paused)`, `Duplicate alerts ignored`, `Processed-event record exists`, `Conditional
    acknowledge update` (`matched`), `Alerts listed` (`cursorReset`, `direction`, `returned`), `Alert not found in
    ward`, `Protocol corpus compared` (`version`, `storedVersion`, `reembed`), `Dependency checked`, `Guidance
    provider responded` (`provider`, `model`, `operation`, `httpStatus`, `apiError`; never the body, message or
    prompt), `Guidance provider skipped: circuit open` (`breaker`).
  - `Dependency checked` is DEBUG only when a dependency is not UP or the check takes ≥ 500 ms. Routine checks are
    TRACE, because `/readyz` runs every few seconds per pod.
  - Measured values and transcript text are health data. They stay out of the logs at every level.
- WARN lines for failures and retries in alert-service's consumer: `Event failed; retrying later` (`attempt`,
  `maxDeliveries`, `retryInMs`, `error`), `Consumption paused: dependency unavailable`, `Consumption resumed` (INFO),
  `Event dead-lettered` (`reason`); for the replay command `Dead-lettered event replayed` and
  `Dead-letter replay finished` (INFO).
- State changes are logged once, not on every probe: a readiness dependency going down (WARN, with the cause) and
  coming back (INFO), with `dependency`/`status`/`previousStatus`; the read replica failing over to the primary and
  back.
- Export of traces, metrics and logs: the OpenTelemetry Java agent is attached in the Docker image (`-javaagent`) and
  sends OTLP to `grafana/otel-lgtm`.
  - Probes and scrapes (`/livez`, `/readyz`, `/metrics`) are hidden from traces and logs. The collector
    (`deploy/otel-collector/otelcol-config.yaml`) drops every trace that contains one, with a tail-sampling `drop`
    policy (the whole trace, so the dependency pings of `/readyz` go too). Neither service writes access-log lines for
    them. Their metrics stay, because the dashboard's health panels use them.
  - In Loki, every CareVoice log line is one JSON object (message, level, logger, service and all structured fields).
    The collector's `transform/json-logs` processor builds it. So Grafana shows the same JSON as stdout, and `| json`
    works: `{service_name="alert-service"} | json | level="WARN"`. The fields stay structured metadata as well.
  - Infrastructure containers (Postgres, MongoDB, RabbitMQ, Redis, Traefik, otel-lgtm, admin tools) are shipped to
    the same Loki by Grafana Alloy (`deploy/alloy/config.alloy`, docker compose only), with `source="docker"` and
    `service_name=<compose service>`: `{source="docker", service_name="rabbitmq"}`.
  - Metrics are also on `/metrics` through Micrometer Prometheus.
  - Agent settings in the Dockerfiles: `OTEL_METRIC_EXPORT_INTERVAL=15000` (matches the 15 s scrape interval, so
    `rate()` over Grafana's `$__rate_interval` always has two samples);
    `OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES=*` (MDC keys such as `requestId` become
    Loki structured metadata: `{service_name=~".+"} | requestId="<id>"`);
    `OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES=true` (the structured fields
    above, which the agent would otherwise drop); `OTEL_METRICS_EXEMPLAR_FILTER=trace_based`.
- Business metrics (Micrometer):
  - documentation-service: `carevoice_transcripts_total{outcome=processed|replayed|rejected}`,
    `carevoice_observations_total{type,source,status}`, `carevoice_ai_fallback_total{result}`,
    `carevoice_ai_request_seconds` (each AI call), `carevoice_db_read_fallback_total` (reads served by the primary
    because the replica was unavailable), `carevoice_ratelimit_fail_open_total`, `carevoice_redis_up`, the outbox
    metrics (section 8) and the Caffeine cache metrics `cache_*{cache="carevoice_resident"|"carevoice_observation_query"}`.
  - alert-service: `carevoice_alerts_total{rule,severity}`, `carevoice_events_processed_total{result=processed|duplicate}`,
    `carevoice_guidance_request_seconds{provider,operation,result}` (each call to Claude, Gemini or Voyage from the
    guidance RAG; `result=ok|declined|error`, with one `externalrequest` log line per call), and the AMQP metrics
    (section 8a).
  - Circuit breakers in both services: `resilience4j_circuitbreaker_*{name=...}`.
- JVM: Micrometer JVM binders (memory, GC, threads, class loader) + container-aware flags in the Dockerfile
  (`-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError`). JFR is available through
  `JAVA_TOOL_OPTIONS`.

## 11. Security

- AES-256-GCM, a random 12-byte IV per value, ciphertext format `<keyId>:<base64(iv|ciphertext|tag)>`. Decryption
  picks the key by its id. Rotation: a new key id for writes; old ids stay readable (`DATA_ENCRYPTION_PREVIOUS_KEYS`).
- Encrypted at rest: resident display names and transcript text. Never encrypted: ids used for lookups.
- Security headers on every response: `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and
  `Cache-Control: no-store` unless a handler sets its own (conditional GETs use `private, no-cache`). No CORS: clients
  are servers and mobile apps only.
- Rate limiting: per user; `/v1/auth/token` per client IP (10 per minute) → 429 + `Retry-After`. The client IP
  comes from `X-Forwarded-For` only when the connection is from a proxy in `TRUSTED_PROXIES` (read right to left,
  trusted hops skipped, never resolved as a hostname); otherwise it is the connection's address, so the header cannot
  be used to fake a fresh address per attempt.
- Secrets come only from the environment. `.env.example` documents them with dummy values. `.env` is git-ignored.
  The local stack uses simple credentials (`admin` / `admin`, demo users `admin123`); never use them anywhere else.

## 12. Test conventions

Libraries: JUnit 5 (`@Nested`, `@ParameterizedTest` + `@FieldSource`/`@MethodSource`), AssertJ, MockK only where a
fake is impractical, embedded databases or Testcontainers (`TestDatabases`), WireMock for outgoing HTTP, Ktor
`testApplication`.

- **One test class per endpoint**, e.g. `@DisplayName("POST /v1/residents/{residentId}/transcripts")`, with
  `@Nested inner` classes in this fixed order and with these exact names (leave out unused ones, never rename them):
  1. `@DisplayName("General Request Structure")`: list endpoints only. Pagination (the Link header is parsed and
     followed: page 1 has no `prev`, `next` moves forward, walking `next` visits every item exactly once and matches
     `X-Total-Count`), limits, cursor validation.
  2. `@DisplayName("Request Validation")`: **every non-2xx** the handler can send (400/401/403/404/409/412/413/415/
     422/428/429). The 5xx smoke test (a fake port that throws) is always the **last** test.
  3. `@DisplayName("Response Validation")`: happy path only. Status, headers (`Location`, `ETag`, `Link`) and the
     **full body** with `assertJsonEquals` (catches missing, wrong and leaked fields). Never a 4xx/5xx here.
  4. `@DisplayName("Business Logic")`: side effects. Row stored, outbox row written, audit row written, event sent,
     cache cleared. **Every successful PATCH is read back** with a GET.
- The authentication and authorization baseline is a set of JUnit 5 **test interfaces** in each service's test
  sources (`AuthenticationContract`, `AuthorizationContract` with default `@Test` methods). The endpoint class
  implements them and supplies the request. They cover: missing token, malformed token, expired token, wrong issuer
  or audience, wrong role → 403, other ward → 404.
- **Data-driven negative tests**: every invalid-input test is
  `@ParameterizedTest(name = "should return 400 when field is {0}")` over a bucket
  (`InvalidJson.required(InvalidJson.string)`, module-specific buckets). Never a hand-picked value, never a `for` loop
  inside one test. Fields with a format check get a positive twin from `ValidValues`.
- Every test name starts with **`should`**:
  `` fun `should return 409 Conflict when the idempotency key is reused with a different body`() ``.
- Every response in an integration test is checked with `OpenApiContract.assertResponseMatches(...)`.
- Deterministic: inject `Clock` and id generators. No `Thread.sleep`; use bounded polling helpers whose timeout
  message names what they waited for.
- A fresh payload per test from a payload factory function. Never change shared fixtures.
- Unit tests for pure logic (rules, backoff, cursor codec, crypto round trip, config validation). Integration tests
  through HTTP with real database engines. Fault-injection tests with WireMock faults and Toxiproxy.
- Every test database is created by running the real migrations (Flyway for Postgres, the migration runner for Mongo).
- Contract tests between the services with Pact: documentation-service is the consumer of
  `POST /v1/events/observations` and of the event message (pact file written to `build/pacts`, copied to `/pacts` at
  the repository root); alert-service verifies both as the provider.

## 13. Ports, images, compose names

| Name | Port | Image |
|---|---|---|
| documentation-service (2 replicas) | 8080 (through Traefik) | built from `documentation-service/Dockerfile` |
| alert-service (2 replicas) | 8081 (through Traefik) | built from `alert-service/Dockerfile` |
| documentation-migrate, alert-migrate (one-shot migrations) | — | the service images, argument `migrate` |
| postgres | 5432 | `postgres:17-alpine` |
| mongo (+ `mongo-init`, which starts the single-member replica set `rs0`) | 27017 | `mongo:8.0` |
| redis | 6379 | `redis:7.4-alpine` |
| rabbitmq | 5672 / 15672 (management) / 15692 (Prometheus) | `rabbitmq:4.1-management-alpine` |
| otel-lgtm (Grafana, OTLP 4317/4318) | 3000 | `grafana/otel-lgtm:0.37.0` |
| alloy (ships infrastructure container logs to Loki) | — | `grafana/alloy:v1.19.2` |
| traefik (load balancer for the 2 replicas of each service) | 8080 / 8081 by port; `CAREVOICE_HTTP_PORT` (default 80) by host name | `traefik:v3.7.14` |
| uptime-kuma (profile `monitoring`) + uptime-kuma-setup (monitors as code) | 3001 | `louislam/uptime-kuma:1.23.16`, `python:3.12-slim` |
| pgadmin (profile `admin`) | 5050 | `dpage/pgadmin4:9.5` |
| mongo-express (profile `admin`) | 8082 | `mongo-express:1.0.2-20-alpine3.19` |
| homepage (profile `tools`, one start page for every UI) | 3002 | `ghcr.io/gethomepage/homepage:v2.2.0` |

All host ports bind to 127.0.0.1 only. Host names (compose): `api.`, `alerts.`, `grafana.`, `rabbitmq.`, `status.`,
`pgadmin.`, `mongo.`, `traefik.`, `home.` + `CAREVOICE_DOMAIN` (default `carevoice.127.0.0.1.nip.io`), on port
`CAREVOICE_HTTP_PORT` (with `CAREVOICE_HTTP_PORT=8000`: `http://api.carevoice.127.0.0.1.nip.io:8000`). The full list
with logins is in `docs/local-access.md`.

## 14. Further binding requirements

**Migrations**
- documentation-service: Flyway, configured explicitly in code: locations `classpath:db/migration`,
  `validateOnMigrate`, `cleanDisabled`, no `outOfOrder`, no `baselineOnMigrate`, table `flyway_schema_history`.
  - `DATABASE_MIGRATE_ON_STARTUP` (default true). When false, the app only validates and refuses to start if a
    migration is pending or changed.
  - Starting the app with the single argument `migrate` runs the migrations and exits. The Kubernetes pre-upgrade Job
    and the compose `migrate` one-shots use it.
  - Never edit a migration that has been applied. Use expand/contract for breaking changes. Seed data is never a
    migration.
  - `V2__outbox_correlation.sql` adds the nullable columns `request_id TEXT` and `traceparent TEXT` to `outbox`.
  - `V3__observation_scope_statistics.sql` adds dependency statistics on `(tenant_id, ward_id, resident_id)`, so the
    planner uses the resident index for its order (docs/indexing.md, finding 1; metadata only, no table rewrite).
  - `V4__statement_time_limits.sql` sets `statement_timeout` and `idle_in_transaction_session_timeout` on the
    application role (section 6).
  - The query plans of the hot queries are checked in CI (`QueryPlanTest`, `AlertQueryPlanTest`, ADR-0024).
- alert-service: a versioned Mongo migration runner (Command pattern).
  - Collection `schema_migrations` (unique `version`; version, description, appliedAt, checksum).
  - Ordered migration objects (`M001CreateAlertIndexes`, `M002CreateProcessedEventsIndexes`,
    `M003CreateProtocolChunkIndexes`).
  - A lock document with an expiry, so pods that start together apply each migration once. The lock is released even
    when the runner is cancelled.
  - It refuses to start when the database is newer than the code. `MONGO_MIGRATE_ON_STARTUP` and the `migrate`
    argument work as above.

**Correlation, MDC and tracing** (no service mesh needed)
- `X-Request-Id` through Ktor CallId (+ `callIdMdc("requestId")`). `userId`, `tenantId` and `wardId` are put into the
  MDC by a route-scoped plugin hooked on `AuthenticationChecked` (they are not known when the call starts).
- The MDC is thread-local. Use `kotlinx-coroutines-slf4j` `MDCContext` around the downstream pipeline and on every
  dispatcher switch (e.g. `withContext(Dispatchers.IO + MDCContext())` in the transaction helper).
- The OpenTelemetry Java agent instruments Ktor, JDBC, Mongo, Lettuce and the RabbitMQ client, and passes on the W3C
  `traceparent`. The outbox stores `request_id` and `traceparent` (read with `io.opentelemetry.api`
  `Span.current()`), so the later, asynchronous publish carries the original request's context (`X-Request-Id` +
  `traceparent` on HTTP, headers on AMQP). Background jobs set their own MDC (`job=...`). Logs keep the MDC keys
  `traceId` and `spanId`.
- Tests: a valid X-Request-Id is echoed, invalid ones (bucket) are replaced; log capture checks that requestId and
  userId are present and that no transcript text is logged.

**Metrics for percentiles and exemplars**
- Ktor MicrometerMetrics with `percentilesHistogram(true)` + SLO buckets (100 ms, 300 ms, 1 s), minimum 5 ms, maximum
  10 s. Every business Timer publishes a percentile histogram (server-side histograms can be combined across pods;
  client-side percentiles cannot). Exemplars come from the OTel agent's `http.server.request.duration` histogram.

**Cursor pagination details**
- Cursor = base64url of `a|<sortKey>|<id>` (after → next) or `b|<sortKey>|<id>` (before → prev).
- Next: `WHERE (sort, id) < (?, ?) ORDER BY sort DESC, id DESC LIMIT limit+1`. Prev: the mirrored ascending query,
  reversed in memory. The extra row shows that there is another page. Malformed → 400.
- Tests: page 1 has no prev; next then prev returns page 1; walking next visits every item once = X-Total-Count; an
  insert between two requests neither shifts nor duplicates items.

**Design patterns, named in KDoc**
- Repository (ports in `application/`, `Postgres*Repository` / `Mongo*Repository` in `infrastructure/`), Adapter
  (Redis, RabbitMQ, HTTP, crypto, AI, clock behind ports), Decorator (`CachingResidentRepository` wraps the Postgres
  repository and Redis invalidation calls it; the circuit-breaking and instrumented guidance providers), Unit of Work
  (the transaction helper for SubmitTranscript), Transactional Outbox, Strategy + Composite (extractors, alert rules),
  Chain of Responsibility (rules → AI), Template Method (`HttpAiVitalExtractor`), Observer (SharedFlow + pub/sub),
  Command (Mongo migrations), Factory (HTTP clients, choice of event publisher), Mapper (explicit
  DTO ↔ domain ↔ row/document functions).

**High availability and replicas**
- documentation-service: optional `DATABASE_READ_URL` (+ optional `DATABASE_READ_USER` / `DATABASE_READ_PASSWORD`,
  which default to the primary's credentials). It is a read-only Hikari pool used ONLY by QueryObservations.
  Everything that must read its own writes stays on the primary. Unset → the primary pool. Replica down → falls back
  to the primary (degraded).
- `REDIS_URL` may be a Sentinel URL (`redis-sentinel://h1:26379,h2:26379/0#mymaster`).
- alert-service MongoClient: write concern majority (+ wtimeout), read concern majority for decision reads, read
  preference primary, retryable reads and writes. The server-selection timeout is short, so a failover shows up as
  503 + Retry-After (timeouts, not-primary and socket errors are mapped to `DependencyUnavailable`). Readiness checks
  for a writable primary. The embedded test mongod is standalone, so tests do not depend on replica-set features.
- Deployment:
  - docker compose: 2 replicas of each service behind Traefik; single Postgres, single Redis, single RabbitMQ, and
    MongoDB as a single-member replica set `rs0`.
  - Kubernetes: Postgres primary + streaming replica (CloudNativePG), MongoDB `rs0` with 3 members, RabbitMQ with 3
    nodes, Redis as a single instance locally (the chart's default is Sentinel), and 2–6 replicas of each service with
    HPA, PDB and spread across zones.

**Containers and DevOps**
- Multi-stage Dockerfiles, build context = repository root:
  - build stage (`eclipse-temurin:21-jdk`, dependency layer first, BuildKit Gradle cache, `installDist`);
  - jlink stage (a minimal JRE from a fixed module list `JRE_MODULES`; jdeps fails on modular jars with
    automatic-module dependencies and misses modules used only through ServiceLoader);
  - runtime `debian:bookworm-slim` (non-root uid 10001, ca-certificates only, OTel agent downloaded and checked
    against its checksum, container-aware JVM flags, `OTEL_METRICS_EXEMPLAR_FILTER=trace_based`, exec-form
    ENTRYPOINT).
  - Build argument `APP_SOURCE`: `app-from-gradle` (default, compiled in Docker) or `app-from-host` (`make up-fast`:
    copies `build/install/<service>` built on the host).
- Local Kubernetes on k3d (`deploy/k8s`, `scripts/k8s-up.sh`, `make k8s-up`, `make k8s-down`, `make k8s-status`):
  - 1 server + 2 agents in two zones; ingress on 127.0.0.1:9080 (http) and 9443 (https).
  - Operators: cert-manager, CloudNativePG (PostgreSQL primary + replica, PgBouncer Pooler) with the Barman Cloud
    plugin, RabbitMQ Cluster Operator (3 nodes). MongoDB `rs0` as a 3-member StatefulSet (no auth locally). Redis as a
    single instance locally (chart default: Sentinel). Grafana otel-lgtm in the namespace `observability`.
  - Overrides in `deploy/k8s/values-k3d.yaml`. Migration Jobs run as pre-install/pre-upgrade hooks.
  - Hosts: `carevoice.127.0.0.1.nip.io:9080`, `grafana.carevoice.127.0.0.1.nip.io:9080`,
    `minio.carevoice.127.0.0.1.nip.io:9080` (MinIO console, backup bucket); self-signed issuer.
  - `values-production.yaml` has the placeholder `api.carevoice.example` and Let's Encrypt ClusterIssuers.
  - Only documentation-service is exposed. alert-service is reached with `kubectl port-forward`.
- PostgreSQL backups in Kubernetes (ADR-0026):
  - The Barman Cloud plugin (`BARMAN_PLUGIN_VER`, default v0.15.1) archives WAL continuously to the `ObjectStore`
    `carevoice-pg-backups` (MinIO, bucket `carevoice-pg-backups`, Secret `carevoice-backup-s3` generated once by
    `make k8s-up`). `archive_timeout = 5min`.
  - `ScheduledBackup` `carevoice-pg-nightly` at 02:00 from the standby, plus one when it is created. Retention 14
    days.
  - Point-in-time recovery always goes into a new cluster. `make k8s-pg-restore-drill` proves it end to end.
  - Prometheus scrapes the CloudNativePG exporter (`carevoice-pg-metrics:9187`) and evaluates
    `deploy/k8s/observability/prometheus-rules.yaml`: `PostgresWalArchivingFailing`, `PostgresWalArchiveBacklog`,
    and `AlertEventsDeadLettered` for the DLQ.
  - MinIO metrics are public locally (`MINIO_PROMETHEUS_AUTH_TYPE=public`, jobs `minio` and `minio-cluster`). The
    dashboard row "Backups (Kubernetes)" shows the time since the last WAL upload, failed uploads, the archive
    backlog, and the bucket's size and object count.
- Uptime Kuma 1.23 (compose profile `monitoring`, status page "CareVoice Status"). The monitors are created as code by
  the idempotent one-shot `uptime-kuma-setup` (`deploy/uptime-kuma/setup.py`): readiness and liveness endpoints, the
  public API by host name, Grafana, Postgres, Mongo, Redis, RabbitMQ. Not deployed in Kubernetes.
- Grafana (otel-lgtm): exemplars on the latency panels (Prometheus datasource `exemplarTraceIdDestinations` → Tempo),
  links between Tempo and Loki, p50/p90/p95/p99 per service and route, latency heatmap, requests per second by status
  class, error ratio, SLO "% under 300 ms", and rows for outbox, AI, Redis, RabbitMQ (queue depth, unacked, retry,
  DLQ) and backups. Variables: service, route, instance. Counters use `sum()`, shared-state gauges `max()`.
- Tests pick their backends with `CAREVOICE_TEST_DB`: `embedded` (default: zonky Postgres, flapdoodle mongod,
  embedded redis-server) or `containers` (in CI). RabbitMQ is always Testcontainers (`TestDatabases.rabbit`); those
  tests are skipped (assumption) when `TestDatabases.dockerAvailable` is false.

**Connection pooling (PgBouncer)**
- Kubernetes only: a CloudNativePG `Pooler` (PgBouncer, `poolMode: transaction`) sits in front of the cluster's `-rw`
  service. documentation-service connects through it (`DATABASE_URL`); the migration Job connects directly
  (`DATABASE_MIGRATION_URL`). Hikari pools stay small per pod; PgBouncer's `default_pool_size` is sized to the
  database. docker compose connects directly (no pooler).
- Rules the code must keep: only transaction-scoped advisory locks (`pg_try_advisory_xact_lock`); no session state
  (`SET`, `LISTEN/NOTIFY`, temp tables); session limits are set on the role (migration V4), not per connection;
  server-side prepared statements can be turned off with `DATABASE_PREPARE_THRESHOLD`.
