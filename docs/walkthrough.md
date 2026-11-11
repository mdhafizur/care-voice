# Walkthrough: demo, requirements, decisions, gaps

A guided tour of CareVoice: a live demo and what each step proves, why the system is built this way, where it is
weak, and how to fix each weakness. Every claim points to code, a test, a metric or a document in this repository.

| Part | What it covers |
|---|---|
| [1. The story in 90 seconds](#1-the-story-in-90-seconds) | The problem and the system in three paragraphs |
| [2. Live demo, step by step](#2-live-demo-step-by-step) | 14 steps; `make demo` runs them interactively |
| [3. Requirements and how they are met](#3-requirements-and-how-they-are-met) | Functional and non-functional requirements, with evidence and gaps |
| [4. Decisions and their trade-offs](#4-decisions-and-their-trade-offs) | What was chosen, why, and what it costs |
| [5. Gaps and how to close them](#5-gaps-and-how-to-close-them) | Honest limits, in order of risk, with a fix for each |
| [6. Questions and answers](#6-questions-and-answers) | Product, backend, SRE, security, data/ML, QA, mobile |
| [7. Engineering: the orthodox and the unorthodox](#7-engineering-the-orthodox-and-the-unorthodox) | What is textbook here, and what is deliberately not |

Background documents: [architecture.md](architecture.md) (diagrams), [CONTRACTS.md](CONTRACTS.md) (the binding
specification), [adr/](adr/README.md) (27 decision records), [availability.md](availability.md) (replicas and
outages), [indexing.md](indexing.md) (indexes with benchmark numbers), [kubernetes.md](kubernetes.md),
[local-access.md](local-access.md) (URLs and logins).

## 1. The story in 90 seconds

> Nurses in care homes spend a large part of every shift on documentation. Much of it is typing numbers they have
> just measured. CareVoice is the backend of a voice-first documentation app. The nurse says "Mrs. Smith, blood
> pressure 185 over 95, pulse 88". The phone turns speech into text, and the backend turns the text into structured
> observations in the resident's record. If a value is dangerous, the ward lead gets an alert within seconds,
> together with the steps from the facility's own protocol.
>
> One principle drives every design decision: **in a care record, a wrong value is worse than a missing one.**
> Fixed rules extract values first. AI is only a fallback, and anything uncertain is stored as `NEEDS_REVIEW` until a
> nurse confirms it. Impossible values are rejected at the domain boundary and never stored.
>
> Technically: two Kotlin/Ktor services. Postgres holds the clinical record, MongoDB holds the alerts, and a
> transactional outbox with RabbitMQ connects them. Redis coordinates the pods, OpenTelemetry sends everything to
> Grafana, and Helm deploys it to Kubernetes. Every service runs as at least two pods, and the demo breaks
> dependencies on purpose to show what still works.

## 2. Live demo, step by step

Prepare the stack once:

```bash
make up-fast                 # stack with 2 replicas per service; rebuilds the images
make reset-demo              # empty demo data, seeded fresh
make monitoring-up           # optional: Uptime Kuma with monitors and the status page
make demo-check              # every service healthy, demo users can log in
```

Useful tabs: Swagger UI (`http://localhost:8080/docs`), Grafana with the "CareVoice" dashboard
(`http://localhost:3000`), Grafana Explore with Loki, and RabbitMQ management (`http://localhost:15672`,
`admin` / `admin`). Step 7 needs a second terminal for the live stream. Without a running stack, the Postman
collection (`postman/CareVoice.postman_collection.json`) documents the same flow, and `make build` proves the
behaviour in tests.

`make demo` runs these steps one at a time. It prints each request before sending it, shows the response, and waits
for Enter. `make demo STEP=7` starts at a given step. The commands below are what it runs, so you can also copy them
by hand.

```bash
DOC=http://localhost:8080      # documentation-service through Traefik (load-balanced over 2 replicas)
ALERT=http://localhost:8081    # alert-service through Traefik
PW=admin123                    # DEMO_PASSWORD from .env
token() { curl -s "$DOC/v1/auth/token" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | jq -r .accessToken; }
```

### Step 1: health, and why there are two endpoints

```bash
curl -s $DOC/livez; curl -s $DOC/readyz | jq; curl -s $ALERT/readyz | jq
```

Expect: `/livez` answers `{"status":"UP"}`. `/readyz` lists `database`, `redis` and `rabbitmq` under `checks`, all
`UP`.

Say: "Liveness never checks dependencies; readiness does. If Postgres goes down, Kubernetes must stop sending traffic
here, not restart healthy pods in a loop. Postgres is critical, so it can make the pod not ready. Redis and RabbitMQ
can only degrade it." (ADR-0017, [availability.md](availability.md))

### Step 2: log in, and see the roles

```bash
ANNA=$(token nurse.anna); CLARA=$(token lead.clara); BEN=$(token nurse.ben)
echo "$ANNA" | jq -R 'split(".")[1] | gsub("-"; "+") | gsub("_"; "/") | . + ("=" * ((4 - length % 4) % 4)) | @base64d | fromjson'
```

Expect: a short-lived JWT (15 minutes, `JWT_TTL_SECONDS`) with `sub`, `tenant`, `ward` and `roles`.

Say: "Tenant and ward are in the token, and every SQL query filters on both. Isolation is enforced in the query, not
only in the use case."

### Step 3: document a transcript

```bash
KEY=$(uuidgen | tr 'A-Z' 'a-z')
curl -si -X POST $DOC/v1/residents/res-1/transcripts -H "Authorization: Bearer $ANNA" \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"transcript":"Mrs. Smith, blood pressure 120 over 75, pulse 80, temperature 37.2 degrees"}'
```

Expect: `201 Created`, a `Location` header, three observations with `source: RULES` and `status: CONFIRMED`, and an
`X-Request-Id` header. Note that id for step 12.

Say: "Rules, not AI: fixed, fast, testable and free. One transaction stores the transcript (encrypted), the
observations, one outbox row per event and an audit row."

### Step 4: the network is unreliable, so retries must be safe

```bash
# same key, same body: the original response comes back
curl -si -X POST $DOC/v1/residents/res-1/transcripts -H "Authorization: Bearer $ANNA" \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"transcript":"Mrs. Smith, blood pressure 120 over 75, pulse 80, temperature 37.2 degrees"}' | head -1
# same key, different body: a client bug, not a retry
curl -si -X POST $DOC/v1/residents/res-1/transcripts -H "Authorization: Bearer $ANNA" \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"transcript":"Mrs. Smith, pulse 95"}' | head -1
```

Expect: `200` with the same body as before, then `409 Conflict`.

Say: "A phone in a basement corridor loses its connection after sending but before it gets the answer. The app
retries with the same key, and the nurse never documents twice. A unique constraint decides: if two pods receive
the retry at the same moment, only one can store it, and the other replays the winner's response. The stored hash of
the body tells a real retry apart from a client bug."

### Step 5: uncertain and impossible values

```bash
post() { curl -s -X POST $DOC/v1/residents/res-1/transcripts -H "Authorization: Bearer $ANNA" \
  -H "Idempotency-Key: $(uuidgen | tr 'A-Z' 'a-z')" -H 'Content-Type: application/json' -d "{\"transcript\":\"$1\"}"; }
post 'Mr. Jones, 135/85, temperature 101' | jq '.observations[] | {vital, confidence, status}'
post 'Mrs. Smith, blood pressure 12 over 10, pulse 80' | jq '{observations: [.observations[].vital], rejected}'
```

Expect: the first gives `NEEDS_REVIEW`, because the confidence is below the threshold (a bare "135/85", a
temperature without a scale). The second keeps the pulse and lists the blood pressure under `rejected`, with a
reason.

Say: "Confidence decides the status, not only the source. And 12 over 10 never reaches the database: the domain
rejects it when the value is created, for rules and AI alike. A gap the nurse notices is better than a wrong number a
doctor trusts."

### Step 6: isolation, tested like an attacker would

```bash
curl -si $DOC/v1/residents/res-1 -H "Authorization: Bearer $BEN" | head -1     # Ben works in ward-b
curl -si $ALERT/v1/alerts -H "Authorization: Bearer $ANNA" | head -1           # nurses do not see alerts
```

Expect: `404` (not 403) for the other ward, and `403` for the missing role.

Say: "We answer 404 instead of 403 across wards, so nobody can probe ids to learn which residents exist elsewhere.
Roles decide what you may do; the scope decides what exists for you."

### Step 7: the ward's live feed (second terminal)

```bash
curl -N $DOC/v1/observations/stream -H "Authorization: Bearer $ANNA"
```

Then, in the first terminal, document a critical blood pressure:

```bash
post 'Mrs. Smith, blood pressure 185 over 95, pulse 88' | jq '.observations[].vital'
```

Expect: the stream prints an `observation` event within a second, with an `id:` line.

Say: "The stream is open on one pod, and the POST may land on the other. Redis pub/sub sends the event to every pod,
and each pod forwards it only to its own clients of that ward. Pub/sub can lose messages, so Postgres stays the
source of truth: a client that reconnects with `Last-Event-ID` gets the missed events replayed. That is why we need
no sticky sessions."

### Step 8: the alert arrives through the outbox and RabbitMQ

```bash
sleep 2; curl -s "$ALERT/v1/alerts?status=OPEN&limit=5" -H "Authorization: Bearer $CLARA" \
  | jq '.items[] | {id, rule, severity, message}'
```

Expect: a `HYPERTENSIVE_CRISIS` alert (systolic ≥ 180). In RabbitMQ management, the queue
`alert-service.observations` shows the message rate.

Say: "The event was written in the same transaction as the observation. So there is no moment where the record
exists and the alert is lost. A relay publishes it. alert-service receives it at least once and removes duplicates
by event id, so a redelivery never creates a second alert."

### Step 9: protocol guidance (RAG)

```bash
ALERT_ID=$(curl -s "$ALERT/v1/alerts?status=OPEN&limit=1" -H "Authorization: Bearer $CLARA" | jq -r '.items[0].id')
curl -s $ALERT/v1/alerts/$ALERT_ID/guidance -H "Authorization: Bearer $CLARA" \
  | jq '{generatedBy, steps, sources: [.sources[] | {id, protocol, section, score}], disclaimer}'
```

Expect: steps from the hypertension protocol, each citing a section id. `generatedBy` is `GEMINI` (or `CLAUDE`) when
an LLM is configured, and `EXTRACTIVE` otherwise.

Say: "Retrieval finds the matching protocol and takes all of its sections. The LLM may only rephrase those excerpts,
and every step must cite one. With Gemini, the allowed citation ids are an enum in the response schema, so the model
cannot even produce an invented citation. A grounding check runs anyway. If it fails, or the LLM is down, the
protocol's own steps are returned. The guidance is advisory only, and labelled so."

### Step 10: concurrency without locks

```bash
ETAG=$(curl -si $ALERT/v1/alerts/$ALERT_ID -H "Authorization: Bearer $CLARA" | grep -i '^etag' | cut -d' ' -f2 | tr -d '\r')
curl -si -X PATCH $ALERT/v1/alerts/$ALERT_ID/acknowledge -H "Authorization: Bearer $CLARA" | head -1
curl -si -X PATCH $ALERT/v1/alerts/$ALERT_ID/acknowledge -H "Authorization: Bearer $CLARA" -H "If-Match: $ETAG" | head -1
curl -si -X PATCH $ALERT/v1/alerts/$ALERT_ID/acknowledge -H "Authorization: Bearer $CLARA" -H "If-Match: $ETAG" | head -1
```

Expect: `428 Precondition Required` without `If-Match`, `200` with it, then `409` because the alert is already
acknowledged. `make demo` also confirms the `NEEDS_REVIEW` observation from step 5: first `412` with an old version,
then `200` with the right one.

Say: "Two ward leads on two tablets. We use optimistic locking through the version, which the API exposes as the
ETag. No row lock is held while a human thinks, and the second writer learns exactly why it lost."

### Step 11: RabbitMQ goes down, documentation does not

```bash
docker compose stop rabbitmq
post 'Mrs. Smith, pulse 132' | jq '.observations[].status'
curl -s $DOC/metrics | grep '^carevoice_outbox_pending'
curl -s $DOC/readyz | jq .checks
docker compose start rabbitmq
# the relay drains the backlog within about a minute (its backoff is capped at 60 s)
until curl -s "$ALERT/v1/alerts?status=OPEN&limit=5" -H "Authorization: Bearer $CLARA" \
  | jq -e '.items[] | select(.rule == "TACHYCARDIA")' >/dev/null; do sleep 5; done; echo "TACHYCARDIA alert is here"
```

Expect: `201` while the broker is down, the pending gauge above 0, and `rabbitmq: DEGRADED` while the service stays
ready. After the restart the backlog drains and the `TACHYCARDIA` alert appears (pulse above 120).

Say: "Nurses keep documenting; alerts are delayed, never lost. The relay's circuit breaker stops hammering the
broker. While it is open, events wait without using up their retries, and the relay gives up on an event only after
10 attempts and 24 hours."

### Step 12: follow one request through both services

In Grafana Explore (Loki):

```
{service_name=~".+"} | requestId="<X-Request-Id from step 3>"
```

Expect: lines from documentation-service (request, transcript processed, outbox publish) and from alert-service
(event processed), all with the same `requestId` and `traceId`. Click the trace id to open the trace in Tempo.

Say: "The request id travels with the event through the outbox and RabbitMQ. So the asynchronous part still belongs
to the nurse's request. Values are structured fields, not message text: `| result="error"` filters across both
services. No transcript text, names or tokens ever reach a log line."

### Step 13: Redis goes down, and Postgres goes down

```bash
docker compose stop redis;    curl -s $DOC/readyz | jq '.status, .checks'; post 'pulse 70' | jq '.observations | length'
docker compose start redis
docker compose stop postgres; curl -si $DOC/readyz | head -1; post 'pulse 70' | jq '.status, .title'
docker compose start postgres
```

Expect: with Redis down, the status is `DEGRADED` and documentation still works (rate limits fail open, caches rely
on their expiry times). With Postgres down, both `/readyz` and the POST answer `503` with `Retry-After`. After the
restart everything recovers without a redeploy. The logs show "Dependency not healthy" once, with the cause, and
later "Dependency healthy again".

Say: "Each dependency is classified as critical or degradable. The worst case for a nurse is a short 503 and a safe
retry with the same key. Data is never lost or stored twice."

### Step 14: kill a pod

```bash
docker compose ps documentation-service
docker kill "$(docker compose ps -q documentation-service | head -1)"
for i in 1 2 3 4 5; do curl -s -o /dev/null -w '%{http_code} ' $DOC/readyz; done; echo
docker compose up -d documentation-service
```

Expect: `200 200 200 ...`: Traefik routes around the dead replica.

Say: "Replicas do not save resources; they cost memory. They let us survive crashes, node drains and deployments.
They also force the services to be stateless. That is why the rate limits, the stream fan-out and the outbox claim
all work across pods."

### Optional deep-dive demos

| Topic | Command | What it shows |
|---|---|---|
| MongoDB outage | `docker compose stop mongo`, then `post 'Mrs. Smith, pulse 132'`; later `docker compose start mongo` | alert-service pauses its consumer (`carevoice_amqp_consumer_paused` = 1), the event waits in `alert-service.observations` without using a retry, and the alert appears a few seconds after Mongo is back. Documentation is not affected |
| Dead letters | `make dlq-replay` | Moves events from the dead-letter queue back to alert-service's queue with a fresh set of retries |
| Query plans | `make db-bench`, then `docs/indexing.md` | 1M rows: keyset 0.018 ms vs OFFSET 12.8 ms; the missing statistics that migration V3 fixed |
| Plan regression tests | `./gradlew :documentation-service:test --tests '*QueryPlanTest'` | CI fails when a query stops using its index |
| Architecture rules | `make test-arch` | Konsist: drivers only in infrastructure, adapters named by technology |
| Mutation testing | `make pitest` | The tests kill mutants in the domain; coverage alone would not show that |
| Contracts | `make pact` | The consumer writes the pact, the provider verifies it |
| Fault injection | `make test-fault` | Toxiproxy cuts and slows RabbitMQ in tests |
| Security | `make security-scan` | ZAP API scan, Trivy image CVEs, gitleaks |
| Load | `make load-smoke`, `make load` | Gatling: 100 nurses documenting at once |
| Kubernetes | `make k8s-up`, `docker stop k3d-carevoice-agent-1` | A node dies: pods move to other nodes, CloudNativePG fails over |
| Backups | `make k8s-pg-restore-drill`, then the MinIO console (`http://minio.carevoice.127.0.0.1.nip.io:9080`) and Grafana's "Backups (Kubernetes)" row | Point-in-time restore into a scratch cluster, checked automatically; the base backups and WAL files in the bucket |

## 3. Requirements and how they are met

### Functional requirements

| # | Requirement | How it is met | Evidence | Gap |
|---|---|---|---|---|
| F1 | Turn a spoken transcript into structured vital signs (blood pressure, pulse, temperature) | Rule-based extraction first; LLM fallback (Claude or Gemini) only when the rules find nothing | `domain/extraction`, `TranscriptProcessor`, ADR-0009, ADR-0025 | English phrasing only; see G5 |
| F2 | Never store an impossible value; flag uncertain ones | The domain rejects impossible values when they are created; low confidence and all AI values are `NEEDS_REVIEW` | `domain/model`, demo step 5 | Plausibility is the same for everyone, not per resident; see G9 |
| F3 | Submitting must be safe to retry | `Idempotency-Key` + unique constraint + stored response and body hash | `transcripts_idempotency_uq`, `SubmitTranscriptTest`, step 4 | The device cannot send the time of measurement; see G4 |
| F4 | A nurse confirms reviewed values; concurrent edits are detected | `PATCH .../confirm` with `If-Match`; 412/428/409 | `ConfirmObservation`, Postman folder 04 | |
| F5 | Resident history and ward-wide queries with filters | Keyset pagination, HTTP `QUERY` (with a POST fallback), one index per query | `PostgresObservationRepository`, `indexing.md` | `X-Total-Count` counts all rows each time; see G7 |
| F6 | Live updates for the ward | SSE, Redis fan-out, `Last-Event-ID` replay from Postgres | `RedisObservationBroadcaster`, step 7 | |
| F7 | Alerts on dangerous values for the ward lead; acknowledgement | Threshold rules in one place; idempotent consumer; acknowledge with optimistic locking | `AlertRules.kt`, `ObservationEventConsumer`, steps 8 and 10 | No escalation when nobody acknowledges; see G8 |
| F8 | Guidance from the facility's protocols for an alert | RAG: retrieval, small-to-big, cited generation, grounding check, extractive fallback | `GetAlertGuidance`, ADR-0023, step 9 | The protocols are demo content, not a managed set of documents |
| F9 | Roles and tenant/ward isolation | JWT with tenant, ward and roles; every query scoped; 404 across wards | `Security.kt`, step 6 | Own token issuer; see G2 |
| F10 | Audit trail and data minimization | Audit row in the business transaction; raw transcript text erased after 30 days | `audit_log`, `TranscriptRetentionJob` | The audit log can be edited without a trace; see G3 |

### Non-functional requirements

| Quality | Target in this design | How it is met | Evidence | Gap |
|---|---|---|---|---|
| **Correctness of the record** | No wrong, lost or duplicated observation | Rules first, domain validation, one transaction, outbox, idempotency, at-least-once delivery + duplicate removal | ADR-0006, ADR-0008, ADR-0009 | |
| **Availability** | Documentation works when anything except Postgres is down | 2+ replicas, PDB, readiness vs liveness, degradable dependencies, HA databases (CloudNativePG, Mongo replica set, RabbitMQ quorum queues); the consumer pauses during a MongoDB outage, retries grow from 5 s to 30 min | `availability.md`, steps 11 to 14 | MongoDB has no backups; see G6 |
| **Latency** | The nurse never waits for AI or other services | The rules path makes no network call; AI has a 3 s (Claude) / 5 s (Gemini) timeout (`AI_TIMEOUT_MS`) and a circuit breaker; alerts are asynchronous; every request has a 30 s deadline (`REQUEST_TIMEOUT_MS`) and every SQL statement a 5 s limit | `HttpAiVitalExtractor`, `RequestDeadline.kt`, migration V4, latency histograms | No formal SLOs yet; see G10 |
| **Scalability** | Linear with pods; constant cost per page | Stateless pods, HPA 2 to 6, keyset pagination, indexes shaped by the queries, small connection pools behind PgBouncer | ADR-0013, ADR-0016, `indexing.md` | |
| **Security** | Least privilege, no injection, safe headers | Parameterized SQL, roles, a service token between services, rate limits, security headers, ZAP/Trivy/gitleaks | CONTRACTS section 11, `make security-scan` | HS256 shared secret, own issuer; see G2 |
| **Privacy (GDPR)** | Health data protected at rest and in logs; kept only as long as needed | AES-256-GCM field encryption with key ids for rotation; retention job; logs carry ids only | `AesGcmFieldEncryptor`, `StructuredLog.kt` | Key in an environment variable; no erasure per resident; see G3 |
| **Observability** | Every request can be explained end to end | OTel agent, structured logs with request and trace ids across the asynchronous step, dashboards, exemplars, logs on every state change | ADR-0015, step 12 | Few alert rules, Kubernetes only; see G10 |
| **Operability** | Deploy and recover without manual steps | Migration Job before rollout, config checked at start (all errors at once), Helm chart, operators, `make k8s-up`; Postgres WAL archiving with point-in-time recovery and a restore drill | ADR-0020, ADR-0022, ADR-0026, `kubernetes.md` | The restore drill runs by hand; see G6 |
| **Testability** | Confidence without mocking the database | Real database engines embedded by default, Testcontainers option, Pact, Konsist, Pitest, Toxiproxy, plan tests, coverage gate (80 % lines, 65 % branches) | ADR-0018, ADR-0019, ADR-0024 | |
| **Evolvability** | Swap a vendor or a store without touching use cases | Hexagonal ports and adapters; Gemini was added as one adapter per port | ADR-0003, ADR-0025 | |
| **Cost** | Pay for AI and capacity only when needed | Rules before LLM calls; HPA follows the peak; partial indexes; the free hashing embedder as the default | ADR-0009, ADR-0023 | |

## 4. Decisions and their trade-offs

Each row: what was chosen, why, what it costs, and what would change the decision. The full reasoning is in the
ADR.

| Decision | Why | Cost | Reconsider when |
|---|---|---|---|
| Kotlin + Ktor (ADR-0002) | Null safety and sealed types for a domain where wrong values are dangerous; Ktor is explicit, small and built on coroutines | Fewer built-in features than Spring; more wiring by hand | The team already runs Spring everywhere |
| Two services (ADR-0003) | Documentation must work when alerting fails; different data shapes | Network calls, eventual consistency, two databases | For a small team I would start with a modular monolith |
| Postgres for documentation (ADR-0004) | Transactions across transcript, observations, outbox and audit; constraints guard correctness | Schema migrations need care (expand/contract) | Never for the clinical record |
| MongoDB for alerts (ADR-0005) | Document-shaped, read-heavy, flexible alert payloads, TTL indexes | A second database to run | A real product of this size: Postgres with JSONB for both |
| Transactional outbox (ADR-0006) | No dual write: the record and the event commit together | A relay, a table that grows (no clean-up yet, G14), at-least-once delivery | Postgres logical decoding (Debezium) once the volume justifies it |
| RabbitMQ (ADR-0007) | Work queue semantics, per-consumer retries and dead-lettering, simple to run | Not a log: no replay of history | Event sourcing, or many consumers replaying: Kafka |
| At-least-once + idempotency (ADR-0008) | Exactly-once does not exist across a network; removing duplicates is cheap and testable | Every consumer needs a key to detect duplicates | Never |
| Rules first, AI as fallback (ADR-0009) | Predictable, explainable, free, instant; AI handles phrasing the rules miss | Rules need maintenance per language | Measured accuracy shows the LLM is reliably better and the review rate drops |
| Keyset pagination, optimistic locking (ADR-0011) | Constant cost per page; no locks held while a human thinks | Clients cannot jump to page N | Never for these access patterns |
| Field-level AES-GCM (ADR-0012) | Names and transcripts unreadable in dumps, backups and admin tools | No search on encrypted fields | A KMS with envelope encryption arrives (G3) |
| Redis + Caffeine (ADR-0014) | Shared limits and fan-out across pods; a local cache with explicit invalidation | One more component; fail-open behaviour to defend | |
| OpenTelemetry agent, no service mesh (ADR-0015) | Traces, metrics and logs without code; a mesh is too much for two services | Agent start-up time and memory | mTLS between many services becomes a requirement |
| Real engines in tests (ADR-0018) | A database mock only tests the mock; embedded engines are fast enough for every build | Slower than unit tests | Never |
| Kubernetes, Helm, operators (ADR-0020) | Self-healing, rolling updates, HPA and HA databases as declarations | Complexity; operators to upgrade | A managed PaaS or managed databases are available |
| RAG with grounding (ADR-0023) | Answers from the facility's own protocols, cited, with a fixed fallback | Retrieval quality must be measured (evaluation set) | |
| Plans are tested (ADR-0024) | An index that the planner stops using fails CI, not production | Tests depend on planner behaviour, so they check only robust properties | |
| Provider as adapter (ADR-0025) | The vendor is a business decision; switching is configuration | Risk of a lowest common denominator, avoided by using each API's own features | |
| WAL archiving with point-in-time recovery (ADR-0026) | The replica covers a lost server, but not a bad migration, a wrong `DELETE` or a lost cluster; a drill proves the restore | Object storage to run; WAL piles up on the primary if archiving fails | A managed Postgres with built-in point-in-time recovery is available |

## 5. Gaps and how to close them

Ordered by risk to a nurse or a resident, then by effort.

### Found and fixed in review

Checking the system against its own documentation found real defects. Each is fixed and guarded by a test:

| Found | Symptom | Fix |
|---|---|---|
| Correlated scope columns misled the planner | The resident history ignored the sort order of its index: 292 buffers instead of 11 | Extended statistics (migration V3), guarded by `QueryPlanTest` |
| Postgres down returned 500 | The promised 503 + `Retry-After` was defined but never raised | Connection-class SQL errors become `ServiceUnavailable` in the JDBC layer; `SqlStatesTest` |
| Silent fallbacks | Replica failover, failing readiness checks and unusable cache messages left no trace | Logged once per state change, with the cause; a counter for replica fallbacks |
| Structured log fields never reached Loki | The OpenTelemetry agent forwarded only MDC keys | `..._CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES=true`; alert-service switched to structured fields |
| No time limits | A slow query, or a request the client had given up on, could keep working without end | 30 s request deadline (`REQUEST_TIMEOUT_MS`), 5 s `statement_timeout` and 15 s idle-in-transaction limit (migration V4), 30 s socket timeout; `RequestDeadlineTest`, `StatementTimeLimitsTest` |
| A long MongoDB outage dead-lettered valid events | After about 25 s of retries every 5 s, events went to the dead-letter queue, with no way back | The consumer pauses while MongoDB is down; growing retry delays (5 s to 30 min); `make dlq-replay`; `ConsumerPauseTest`, `RetryTiersTest`, `DeadLetterReplayerTest` |
| The outbox gave up too early | Valid events were marked `DEAD` after about 1.5 minutes of failures when the circuit breaker did not open, e.g. on a quiet ward | `DEAD` only after 10 attempts **and** 24 hours; `OutboxRelayIntegrationTest` |
| A dead guidance LLM slowed every request | Each guidance request waited 15 to 20 s for the timeout before the fallback | Circuit breakers on the LLM writer and the embedder; `GuidanceCircuitBreakersTest` |
| No backups | A bad migration or a lost cluster would lose the clinical record | WAL archiving, nightly base backups, point-in-time recovery, `make k8s-pg-restore-drill` (ADR-0026) |

### Still open

| # | Gap | Risk | Fix | Effort |
|---|---|---|---|---|
| G1 | Dead-lettered events raise an alert only in Kubernetes, and are replayed by hand | A dead letter is a vital sign without its alert | Done so far: the consumer pauses during MongoDB outages, retries grow to about 43 minutes, `make dlq-replay`, the Prometheus rule `AlertEventsDeadLettered`. Still to do: the same alert in the compose Grafana, and a replay Job in the Helm chart | 1 day |
| G2 | Own token issuer with an HS256 shared secret; no refresh tokens or device binding | A leaked secret signs tokens for every tenant | OIDC provider (Keycloak, Auth0, Entra ID), RS256 with JWKS rotation, short access tokens + refresh tokens, device-bound tokens for shared ward phones | 1 to 2 weeks |
| G3 | Encryption key in an environment variable; no erasure per resident; the audit log is not tamper-evident | A leaked key exposes all data; erasure requests are manual; a DBA could edit the audit log | Envelope encryption with a KMS (data keys per tenant); crypto-shredding: one key per resident, and deleting the key erases the resident; hash-chained audit rows shipped to write-once (WORM) storage | 2 weeks |
| G4 | The server sets `recordedAt`; an offline device cannot say when a value was measured | A measurement uploaded 2 hours later appears as "now", which distorts the trend | Accept a device `measuredAt` within a bounded clock difference, store both times, show "documented late"; the idempotency key already makes offline queues safe | 2 days |
| G5 | The extraction rules understand English only | Not usable where care is documented in German, with dialects and decimal commas like "37,2" | Language packs for the rules (`Blutdruck 120 zu 80`, `Puls`, decimal comma), an evaluation set per language in CI, a language hint for the LLM fallback | 1 week per language |
| G6 | MongoDB has no backups; the Postgres restore drill runs by hand | Alerts are lost if MongoDB is lost completely; a drill nobody runs stops proving anything | Postgres is covered (ADR-0026: WAL archiving, nightly base backups, point-in-time recovery, `make k8s-pg-restore-drill`). Still to do: MongoDB snapshots or `mongodump` to the same bucket, and the drill as a monthly CronJob that also runs the smoke test | 2 days |
| G7 | `X-Total-Count` counts every row in the scope on every page | Latency grows with the size of the ward | Count on the first page only, or capped ("10,000+"), or cached per ward with the existing invalidation | 1 day |
| G8 | No escalation when an alert stays unacknowledged; no window to suppress repeats | A crisis alert at night with nobody looking; alert fatigue from repeated values | Escalation policy (after N minutes the next person, then the on-call nurse); suppress repeats per resident and rule within a time window | 1 week |
| G9 | The thresholds are the same for every resident | A resident with a known baseline (COPD, chronic hypertension) produces noise or is missed | Per-resident thresholds set by a clinician, versioned, with the default as fallback; trend rules ("rising 20 % in 24 h") | 2 weeks |
| G10 | No SLOs or error budgets; only three alert rules (backups, dead letters), in Kubernetes only | Users notice regressions first | SLOs per user journey (documenting a transcript: 99.9 % success, p95 < 500 ms), multi-window burn-rate alerts in Grafana, alerts on outbox lag | 3 days |
| G11 | Tenant isolation is enforced by application SQL only | One missing filter in a new query leaks data | Postgres row-level security keyed on a session variable as a second line of defence; a Konsist rule that every repository query mentions `tenant_id` | 3 days |
| G12 | No interoperability export | The facility's existing documentation system does not receive the values | A FHIR `Observation` adapter with LOINC codes (8480-6 systolic, 8462-4 diastolic, 8867-4 heart rate, 8310-5 body temperature), driven by the existing events | 1 week |
| G13 | Supply chain: an SBOM exists, but the images are not signed | A tampered image could be deployed | Sign images with cosign, verify them with an admission policy, Renovate for dependency updates | 2 days |
| G14 | Nothing deletes `PUBLISHED` outbox rows | The outbox table grows without limit; claims and the pending count slowly get more expensive | A retention job that deletes `PUBLISHED` rows older than N days in small batches, like the transcript-erasure job (`TranscriptRetentionJob`) | 1 day |

A regulatory point worth raising: software that raises clinical alerts can fall under the EU Medical Device
Regulation (MDR). The design keeps alerts and guidance explicitly advisory, and the thresholds live in one place that
can be reviewed (`AlertRules.kt`). That is what a clinical safety review would need. A real product would decide the
MDR classification with regulatory experts before shipping alerts.

## 6. Questions and answers

Short answers; the linked document has the long form.

### Product

- **Why rules before AI?** A wrong value in a care record is worse than a missing one. A nurse can understand a rule
  ("it heard 120 over 75"), and rules are instant, free and testable. The LLM handles the rare phrasing and is always
  reviewed.
- **How do you know extraction is good enough?** Measure, do not guess. Keep an evaluation set of real (anonymized)
  transcripts per language, check precision and recall per vital type in CI, and watch the `NEEDS_REVIEW` rate and
  the nurses' corrections in production. Precision matters more than recall here.
- **What would you build next?** G4 (offline timestamps) and G5 (German) make it usable on a real ward. G8
  (escalation) and G10 (SLOs and alerts) make sure an alert is acted on and a failure is noticed.

### Backend

- **Why the outbox and not "save, then publish"?** The process can die between the commit and the publish. Then the
  record exists and the alert never comes. The outbox makes the event part of the same transaction.
- **Exactly-once?** Not across a network. At-least-once delivery plus idempotent consumers gives an exactly-once
  effect: `processed_events` and the unique `{eventId, rule}` index.
- **How do two pods not publish the same outbox row?** `FOR UPDATE SKIP LOCKED` claims rows and leases them. A fence
  on the lease rejects late updates from a pod that stalled.
- **Why keyset pagination?** Constant cost per page (8 buffers at page 500 instead of 3,966 with OFFSET), and stable
  results while new observations arrive.
- **Why the HTTP QUERY method?** A filter is a safe, idempotent read with a body. `QUERY` says exactly that and can
  be cached. The POST fallback exists for clients and proxies that do not know it yet.
- **What happens when a request takes too long?** The 30 s request deadline cancels the handler's coroutine and
  answers 503 with `Retry-After`. Cancellation stops every suspending call it started. A blocking SQL statement
  cannot be cancelled that way, so Postgres limits each statement to 5 s.

### SRE and platform

- **What happens when X is down?** Each dependency is either critical or degradable: [availability.md](availability.md).
- **Why does liveness ignore the database?** Otherwise a database outage would restart every healthy pod, and the
  restarts would make recovery slower.
- **How do you deploy without downtime?** The migration Job runs first (only additive changes), the rolling update
  waits for `/readyz`, a PodDisruptionBudget limits disruption, and on SIGTERM readiness turns red first so running
  requests can finish.
- **How do you find a slow request?** Exemplars link the latency histogram to the trace, and the trace id is on every
  log line of both services.
- **How do you recover lost data?** Restore Postgres into a new cluster at a chosen second, from the WAL archive.
  The worst case is about 5 minutes of writes (`archive_timeout`), and `make k8s-pg-restore-drill` proves it works.

### Security and privacy

- **Where is personal data, and who can read it?** Names and transcripts are AES-GCM ciphertext in the database;
  only the service decrypts them. Logs carry ids, never content. Parser errors are not logged, because they can echo
  the request body.
- **What if the encryption key leaks?** Today: rotate it (key ids keep old data readable during re-encryption).
  Better: G3, envelope encryption with a KMS and keys per resident.
- **Right to erasure?** Raw transcript text is already erased after 30 days. Full erasure per resident is G3's
  crypto-shredding.
- **What did the security scan find?** Show `build/security` and explain how each finding was handled.

### Data and ML

- **How does the RAG avoid hallucinations?** Excerpts only, a citation required for every step, citation ids limited
  by the response schema (Gemini), a grounding check and an extractive fallback. A wrong step with a correct
  citation is still possible; the disclaimer and the ward lead's judgment remain.
- **How do you evaluate retrieval?** `ProtocolRetrievalEvaluationTest`: every alert rule must retrieve its protocol
  with a margin, and unrelated text must stay below the threshold. It runs in every build.
- **Why a hashing embedder by default?** It is predictable, works offline and is free, so CI and demos never depend
  on an API key. Semantic embedders (Gemini, Voyage) are one setting away.
- **Why limit thinking on Gemini?** Reading numbers out of a sentence needs no reasoning. One test call spent 283
  thinking tokens on a 10-token answer.

### QA

- **How do you test the database code?** Against real Postgres and MongoDB, embedded by default and as the
  production images with `make test-containers`, including query plans.
- **How do you know the tests are good?** Mutation testing on the domain (`make pitest`). Coverage is a gate, not the
  goal.
- **How are the two services kept compatible?** Pact consumer and provider tests, plus OpenAPI response validation in
  every endpoint test.

### Mobile and client

- **What should the app do when offline?** Queue each transcript with its idempotency key and send it later; the
  server already makes that safe. G4 adds the measurement time from the device.
- **What does the client do on 503?** Retry with backoff and the same key, and respect `Retry-After`.
- **How do errors look?** RFC 9457 problem details with a stable `type`, field-level `errors`, and the `requestId`
  for support.

## 7. Engineering: the orthodox and the unorthodox

### Orthodox, on purpose

Textbook patterns, used because they work, without decoration:

- Hexagonal architecture: the domain has no framework imports, and Konsist tests enforce it.
- Transactional outbox, idempotency keys, optimistic locking, keyset pagination.
- RFC 9457 problem details and precise status codes (404 versus 403; 409 versus 412 versus 428).
- Twelve-factor configuration, checked at start-up, with every error reported at once.
- Expand/contract migrations, with the migration Job before the rollout.
- Architecture Decision Records for every non-obvious choice, including the costs.
- Health endpoints split into liveness and readiness.
- Timeouts, retries with backoff and jitter, and circuit breakers on every remote call.

### Unorthodox, and why

Less common choices, each with the reason it is worth the surprise:

- **A wrong value is treated as worse than a missing one.** Most systems try to capture as much as possible. This
  one rejects impossible values at the domain boundary and sends every uncertain value to a human.
- **Query plans are tests.** Indexes are usually designed once and then trusted. Here CI runs `EXPLAIN` on the exact
  SQL the repositories send and fails when a query stops using its index. The benchmark behind this found a real
  defect: correlated columns made the planner ignore the right index. Extended statistics fixed it, and a test
  guards it.
- **The benchmark reads the schema from the catalog.** It cannot drift from the migrations, because it has no copy
  of them.
- **Making illegal states unrepresentable, applied to an LLM.** Instead of only asking the model not to invent
  citations, the response schema allows only the ids that were sent.
- **AI is optional at every point.** Every AI feature has a fixed fallback, and the default configuration needs no
  API key at all. The system can be demoed and tested offline.
- **Deliberate fail-open.** Rate limits fail open when Redis is down, with an in-memory limit for login: nurses
  documenting matters more than a strict limit. It is a stated, logged and measured decision, not an accident.
- **An outage is not a failure of the message.** When MongoDB is down, the consumer pauses and the events wait in the
  queue; when the broker is down, the outbox parks its rows. Retries are kept for errors that retrying can fix.
- **The demo breaks its own dependencies.** Resilience is shown, not claimed. The same goes for backups: the restore
  drill proves them.
- **Mutation testing and architecture tests** in a small project: they are cheap at this size and expensive to add
  later.
- **Privacy by construction in the logging API.** The structured log helper takes ids, counts and durations; there
  is no convenient way to log a transcript.
- **Replaceability measured in files.** Adding Gemini touched one adapter per port and the configuration; no use
  case changed. That is the test of whether the architecture is real.
