# Architecture

The diagrams are written in Mermaid. GitHub and IntelliJ (with the Mermaid plugin) show them as pictures. The same
sources are in `docs/diagrams/*.mmd`, and each one is also saved as an SVG:
`npx @mermaid-js/mermaid-cli -i <file>.mmd -o <file>.svg -b white`. Keep the code blocks below and the `.mmd` files
identical. The reasons behind each part are in the [Architecture Decision Records](adr/README.md).

1. [System architecture](#1-system-architecture)
2. [Sequence: submit a transcript (happy path)](#2-sequence-submit-a-transcript)
3. [Sequence: failures, retries and dead-lettering](#3-sequence-failures-retries-and-dead-lettering)
4. [Sequence: concurrent confirmation (optimistic locking)](#4-sequence-concurrent-confirmation)
5. [Deployment on Kubernetes](#5-deployment-on-kubernetes)
6. [Sequence: protocol guidance for an alert (RAG)](#6-sequence-protocol-guidance-for-an-alert-rag)

## 1. System architecture

A nurse sends a transcript to documentation-service. The pure `domain` module finds and checks the vital signs, and
the service stores them in PostgreSQL together with outbox rows. The outbox relay publishes events to RabbitMQ.
alert-service reads them, ignores duplicates, and stores alerts in MongoDB. Redis is shared by all pods of
documentation-service. The OpenTelemetry agent sends traces, metrics and logs to Grafana.

For an alert, alert-service can also return guidance: steps taken from the care home's protocols, optionally
rewritten by Claude or Gemini, and always with their sources. In Kubernetes, PostgreSQL also copies its write-ahead
log (WAL) to object storage, so the database can be restored to any point in time.

```mermaid
flowchart LR
    subgraph clients["Clients"]
        app["Nurse app<br/>(speech-to-text on the phone)"]
        lead["Ward lead"]
        gatling["Gatling load tests"]
    end

    ingress["Traefik<br/>(TLS from cert-manager in Kubernetes)"]

    subgraph doc["documentation-service · Ktor · 2–6 pods"]
        direction TB
        dapi["api<br/>routes · login · errors (RFC 9457)<br/>rate limit · request deadline"]
        dapp["application<br/>use cases · ports"]
        ddom["domain (pure Kotlin)<br/>normalize → extract → validate → review policy"]
        dinfra["infrastructure<br/>JDBC · AES-GCM · Caffeine · Redis · AI client"]
        relay["Outbox relay<br/>SKIP LOCKED · backoff · circuit breaker"]
        dapi --> dapp --> ddom
        dapp --> dinfra
    end

    subgraph alert["alert-service · Ktor · 2–6 pods"]
        direction TB
        consumer["AMQP consumer<br/>manual ack · retry tiers<br/>pauses while MongoDB is down"]
        aapi["api<br/>list · acknowledge · guidance"]
        rules["alert rules<br/>Strategy + Composite"]
        guidance["protocol guidance (RAG)<br/>retrieve → write → check citations"]
        consumer --> rules
        aapi --> rules
        aapi --> guidance
    end

    subgraph data["Data stores"]
        pgb["PgBouncer<br/>(transaction mode, Kubernetes only)"]
        pg[("PostgreSQL<br/>users · residents · transcripts<br/>observations · outbox · audit")]
        redis[("Redis<br/>rate limits · cache invalidation<br/>live updates (SSE)")]
        mq{{"RabbitMQ<br/>quorum queue · retry tiers · DLQ"}}
        mongo[("MongoDB rs0<br/>alerts · processed_events<br/>protocol_chunks")]
        s3[("Object storage (MinIO)<br/>WAL + base backups<br/>Kubernetes only")]
    end

    llm["AI provider: Claude or Gemini<br/>(extraction fallback + guidance, optional)"]
    emb["Embeddings: Voyage or Gemini<br/>(optional; default: local hashing)"]

    subgraph obs["Observability"]
        otel["OTel Java agent<br/>(in every service pod)"]
        alloy["Grafana Alloy<br/>container logs (compose)"]
        lgtm["Grafana otel-lgtm<br/>Prometheus · Tempo · Loki"]
        kuma["Uptime Kuma<br/>status page (compose)"]
    end

    app -- "HTTPS · JWT · Idempotency-Key" --> ingress
    gatling --> ingress
    ingress --> dapi
    lead -- "JWT (WARD_LEAD)" --> aapi

    dinfra --> pgb --> pg
    dinfra <--> redis
    dinfra -. "only when the rules find nothing" .-> llm
    relay -- "claim PENDING rows" --> pg
    relay -- "publish + confirms<br/>observation.recorded.v1" --> mq
    relay -. "alternative: HTTP POST /v1/events/observations" .-> aapi
    mq -- "deliver" --> consumer
    rules --> mongo
    guidance --> mongo
    guidance -. "GUIDANCE_AI_ENABLED" .-> llm
    guidance -. "GUIDANCE_EMBEDDER=voyage | gemini" .-> emb
    pg -. "WAL archiving<br/>(Barman Cloud plugin)" .-> s3

    doc -. "traces · metrics · logs (OTLP)" .-> otel
    alert -.-> otel
    otel -.-> lgtm
    alloy -.-> lgtm
    kuma -. "/readyz · /livez · ports" .-> ingress
```

Read it from left to right. The **synchronous path** (solid lines: client → documentation-service → PostgreSQL)
answers the nurse. The **asynchronous path** (outbox → RabbitMQ → alert-service → MongoDB) raises alerts. The nurse
never waits for it, and it never makes her request fail, even when alert-service is down. Decisions:
[ADR-0003](adr/0003-two-services-hexagonal-architecture.md), [0004](adr/0004-postgresql-for-documentation-service.md),
[0005](adr/0005-mongodb-for-alert-service.md), [0006](adr/0006-transactional-outbox.md),
[0007](adr/0007-rabbitmq-as-event-transport.md), [0025](adr/0025-model-provider-behind-ports.md),
[0026](adr/0026-postgres-backups-wal-archiving.md).

## 2. Sequence: submit a transcript

```mermaid
sequenceDiagram
    autonumber
    actor Nurse as Nurse app
    participant DS as documentation-service
    participant R as Redis
    participant PG as PostgreSQL
    participant AI as AI provider<br/>(Claude or Gemini)
    participant Relay as Outbox relay<br/>(any pod)
    participant MQ as RabbitMQ
    participant AS as alert-service
    participant M as MongoDB

    Nurse->>DS: POST /v1/residents/res-1/transcripts<br/>Authorization: Bearer JWT · Idempotency-Key · X-Request-Id
    DS->>DS: check the token (401) → check the role (403)
    DS->>R: rate limit counter (Lua script)
    R-->>DS: allowed (else 429 + Retry-After)
    DS->>PG: resident in the caller's ward? (else 404)
    DS->>PG: idempotency key used before?
    alt same key, same body
        PG-->>DS: stored response
        DS-->>Nurse: 200 + Idempotent-Replayed: true
    else same key, different body
        DS-->>Nurse: 409 Conflict
    else new key
        DS->>DS: domain: normalize → rule extractors → smart constructors
        opt the rules found nothing and AI is enabled
            DS->>AI: extract vital signs (AI_TIMEOUT_MS)
            AI-->>DS: candidates → validated, always NEEDS_REVIEW<br/>(any AI failure → no AI result, never an error)
        end
        rect rgb(230, 240, 255)
            note over DS,PG: ONE transaction (Unit of Work)
            DS->>PG: INSERT transcript (AES-GCM text, idempotency key, response)
            DS->>PG: INSERT observations (CONFIRMED / NEEDS_REVIEW)
            DS->>PG: INSERT outbox row per observation (+ request_id, traceparent)
            DS->>PG: INSERT audit row
            DS->>PG: COMMIT
        end
        DS->>R: PUBLISH cache invalidation + observation (live updates)
        DS-->>Nurse: 201 Created + Location
    end

    loop every pod, all the time
        Relay->>PG: SELECT … FOR UPDATE SKIP LOCKED LIMIT 50<br/>move next_attempt_at forward by a 30 s lease
        Relay->>MQ: publish observation.recorded.v1<br/>(message_id = eventId, traceparent)
        MQ-->>Relay: publisher confirm (ack)
        Relay->>PG: UPDATE outbox SET status = PUBLISHED
    end

    MQ->>AS: deliver (prefetch limits unacked messages)
    AS->>AS: validate the event · evaluate the rules (e.g. systolic ≥ 180 → CRITICAL)
    AS->>M: INSERT processed_events {eventId, completed: false}<br/>(the unique eventId decides who processes it)
    alt first delivery
        AS->>M: INSERT alerts (unique eventId + rule, writeConcern majority)
        AS->>M: mark processed_events completed
    else duplicate, record completed
        M-->>AS: duplicate key → nothing to do
    else duplicate, record incomplete (an earlier attempt crashed)
        AS->>M: run the idempotent alert insert again, mark completed
    end
    AS->>MQ: basicAck
```

Things to point out in a walkthrough:
- **Order of the checks (steps 2–5):** 401 → 403 → 404 → business rules. A resident of another ward gives 404, not
  403, so a caller cannot find out which residents exist elsewhere.
- **Idempotency:** the key and the stored response are saved in the same transaction as the data. A repeated request
  gets the original response back ([ADR-0008](adr/0008-idempotency-and-at-least-once-delivery.md)).
- **The AI never blocks documentation:** it runs only when the rules find nothing, and it runs before the transaction,
  so a slow model never holds a database connection. Every AI failure just means "no AI result".
- **One transaction** writes the data *and* the outbox rows, so either both exist or neither does. Nothing is sent to
  RabbitMQ inside the transaction ([ADR-0006](adr/0006-transactional-outbox.md)).
- The nurse gets **201 before** alert-service has seen anything: the alert path is asynchronous.
- The `traceparent` saved on the outbox row continues the same trace in alert-service
  ([ADR-0015](adr/0015-observability-and-tracing-without-a-service-mesh.md)).
- alert-service writes the `processed_events` record **first** and marks it incomplete. The unique index then decides
  which delivery processes the event. If a pod crashes between the two writes, the next delivery finishes the work.
- Time limits: the whole request has a deadline (`REQUEST_TIMEOUT_MS`, 30 s by default, then 503), and each Postgres
  statement is stopped after 5 s.

## 3. Sequence: failures, retries and dead-lettering

```mermaid
sequenceDiagram
    autonumber
    participant Relay as Outbox relay
    participant PG as PostgreSQL
    participant MQ as RabbitMQ
    participant Q as alert-service.observations
    participant RQ as retry tiers (5 s … 30 min)
    participant DLQ as DLQ
    participant AS as alert-service
    participant M as MongoDB

    note over Relay,MQ: Publishing side: the outbox holds the events, nurses keep documenting
    Relay->>MQ: publish
    MQ--xRelay: nack / timeout / broker down
    Relay->>PG: attempts + 1, next try after<br/>random(0, min(60 s, 0.5 s · 2^(attempts − 1)))
    opt 50 % of the last 20 calls failed
        Relay->>Relay: circuit OPEN for 30 s: rows wait, no attempt counted
    end
    opt 4xx from alert-service (HTTP transport), or 10 attempts AND older than 24 h
        Relay->>PG: status = DEAD (metric + Grafana alert)
    end

    note over Q,M: Consuming side: at-least-once, idempotent
    Q->>AS: deliver
    AS->>M: insert
    M--xAS: primary unavailable
    alt Mongo health check says DOWN (outage)
        AS->>Q: cancel the subscription, basicNack(requeue = true), no attempt counted
        AS->>M: health check every 5 s
        AS->>Q: subscribe again once MongoDB is healthy
    else MongoDB answers again (short blip)
        AS->>RQ: publish to the tier for this attempt (x-carevoice-attempt + 1), then basicAck
        RQ-->>Q: after the tier's TTL, routed back to this queue only
        Q->>AS: deliver again
    end
    alt MongoDB is back
        AS->>M: insert succeeds
        AS->>Q: basicAck
    else delivery 6 failed (~43 min), or invalid JSON / unknown schemaVersion
        AS->>DLQ: publish to carevoice.events.dlx
        AS->>Q: basicAck (replay later: make dlq-replay)
    end
```

There are two independent retry loops:
- The **outbox** retries publishing. It waits longer after each failure (exponential backoff with random jitter) and
  uses a circuit breaker. A row becomes `DEAD` only after 10 attempts *and* 24 hours.
- **RabbitMQ** retries consuming. A failed event waits in one of five retry queues (5 s, 30 s, 2 min, 10 min, 30 min)
  and goes to the dead-letter queue (DLQ) only when delivery 6 fails. `make dlq-replay` sends it back. When MongoDB is
  really down, the consumer pauses instead, so the events wait in the queue and use up no retries.

Neither loop loses a message, and both depend on the consumer ignoring duplicates.

## 4. Sequence: concurrent confirmation

```mermaid
sequenceDiagram
    autonumber
    actor A as Nurse A
    actor B as Nurse B
    participant DS as documentation-service
    participant PG as PostgreSQL

    A->>DS: GET /v1/residents/res-1/observations
    DS-->>A: 200 · item version 0
    B->>DS: GET /v1/residents/res-1/observations
    DS-->>B: 200 · item version 0

    A->>DS: PATCH /v1/observations/{id}/confirm<br/>If-Match: "0"
    DS->>PG: UPDATE … SET status = CONFIRMED, version = version + 1<br/>WHERE id = ? AND version = 0
    PG-->>DS: 1 row
    DS-->>A: 200 · ETag: "1"

    B->>DS: PATCH /v1/observations/{id}/confirm<br/>If-Match: "0"
    DS->>PG: UPDATE … WHERE id = ? AND version = 0
    PG-->>DS: 0 rows
    DS-->>B: 412 Precondition Failed (problem+json)

    B->>DS: PATCH … (no If-Match)
    DS-->>B: 428 Precondition Required

    note over B,DS: With the current version but already CONFIRMED: 409 Conflict
```

No database lock is held between the GET and the PATCH. The `version` in the `WHERE` clause finds the conflict
(optimistic locking, [ADR-0011](adr/0011-keyset-pagination-and-optimistic-locking.md)).

## 5. Deployment on Kubernetes

```mermaid
flowchart TB
    browser(("Browser / Postman<br/>*.carevoice.127.0.0.1.nip.io:9080")) --> lb

    subgraph k3d["k3d cluster 'carevoice': 1 server + 2 agents (Docker containers), zones a and b"]
        lb["k3d load balancer<br/>127.0.0.1:9080 → 80, :9443 → 443"]

        subgraph ks["namespace kube-system"]
            traefik["Traefik ingress (comes with k3s)<br/>carevoice. · grafana. · minio."]
            dns["CoreDNS · metrics-server"]
        end

        subgraph ns_app["namespace carevoice"]
            subgraph dsd["Deployment documentation-service<br/>HPA 2–6 · PDB · spread over nodes/zones"]
                ds1["pod"]
                ds2["pod"]
            end
            subgraph asd["Deployment alert-service<br/>HPA 2–6 · PDB · not exposed"]
                as1["pod"]
                as2["pod"]
            end
            np["NetworkPolicies on the service pods:<br/>deny by default + allow-list"]
            jobs["Migration Jobs<br/>Helm pre-install / pre-upgrade"]
            pooler["Pooler carevoice-pg-pooler-rw<br/>PgBouncer ×2 · transaction mode"]
            pgc[("Cluster carevoice-pg<br/>primary + replica<br/>WAL archiving · nightly backup")]
            minio[("MinIO<br/>bucket carevoice-pg-backups")]
            rmq{{"RabbitmqCluster<br/>3 nodes · quorum queues"}}
            mgo[("StatefulSet carevoice-mongodb<br/>rs0 · 3 members")]
            rds[("Redis<br/>(single instance locally)")]
        end

        subgraph ns_obs["namespace observability"]
            lgtm["Grafana otel-lgtm<br/>Prometheus scrapes services,<br/>Postgres, RabbitMQ, MinIO"]
        end

        subgraph ns_ops["operator namespaces"]
            ops["cert-manager · cnpg-system<br/>(CloudNativePG + Barman Cloud plugin)<br/>rabbitmq-system"]
        end
    end

    lb --> traefik
    traefik --> ds1 & ds2
    traefik --> lgtm
    traefik -- "console only" --> minio
    ds1 & ds2 --> pooler --> pgc
    jobs -- "direct (DATABASE_MIGRATION_URL)" --> pgc
    jobs --> mgo
    ds1 & ds2 --> rds
    ds1 & ds2 --> rmq
    rmq --> as1 & as2
    as1 & as2 --> mgo
    pgc -- "WAL + base backups" --> minio
    ds1 & ds2 & as1 & as2 -. "OTLP :4318" .-> lgtm
    ops -. "manage" .-> pgc & pooler & rmq
```

The local cluster runs on k3d: three nodes (Docker containers) in two zones, so replicas really spread out, and
stopping a node shows self-healing. The ingress (127.0.0.1:9080/9443) exposes three host names: the
documentation-service API (`carevoice.`), Grafana (`grafana.`) and the MinIO console (`minio.`). alert-service is not
exposed; use `kubectl port-forward` to reach it.

- The services connect to PostgreSQL through PgBouncer. The migration Jobs connect directly
  ([ADR-0016](adr/0016-connection-pooling-pgbouncer-and-mongodb.md)).
- The operators manage the PostgreSQL cluster, the pooler and RabbitMQ.
- PostgreSQL archives its WAL and a nightly base backup to MinIO through the Barman Cloud plugin
  ([ADR-0026](adr/0026-postgres-backups-wal-archiving.md)). `make k8s-pg-restore-drill` proves the restore works.

Step by step: [kubernetes.md](kubernetes.md). Locally, the same services also run in docker compose, with 2 replicas
each behind Traefik ([ADR-0020](adr/0020-deployment-kubernetes-helm-uptime-kuma.md)).

## 6. Sequence: protocol guidance for an alert (RAG)

```mermaid
sequenceDiagram
    autonumber
    actor Lead as Ward lead
    participant AS as alert-service
    participant M as MongoDB
    participant E as Embedder<br/>(local hashing | Voyage | Gemini)
    participant C as Writer: Claude or Gemini<br/>(optional)

    note over AS,M: At startup: protocols (markdown) → one chunk per section → embed → protocol_chunks<br/>(skipped when the corpus version has not changed)

    Lead->>AS: GET /v1/alerts/{alertId}/guidance (JWT, WARD_LEAD)
    AS->>M: find the alert in the caller's ward (else 404)
    AS->>M: load the protocol chunks of this embedding model
    AS->>E: embed one query per aspect<br/>clinical: "hypertensive crisis … 185/95 mmHg"<br/>review: only for an unconfirmed value
    E-->>AS: query vectors (embedder down → 503 at once, circuit breaker)
    AS->>AS: cosine similarity → best protocol per aspect<br/>→ all its sections (small-to-big), review protocol first
    alt no section above the threshold
        AS-->>Lead: 200 NO_MATCHING_PROTOCOL (no steps, no sources)
    else protocol found
        opt GUIDANCE_AI_ENABLED
            AS->>C: alert + excerpts with ids: "cite every step, or NO_GUIDANCE"
            C-->>AS: steps with citations
        end
        AS->>AS: citation check: steps may cite retrieved sections only<br/>failed, declined, error or open breaker → copy the protocol's own steps
        AS-->>Lead: 200 FOUND · generatedBy CLAUDE | GEMINI | EXTRACTIVE<br/>steps[] with citations · sources[] · disclaimer
    end
```

Retrieval picks the protocol. The writer (Claude or Gemini) only rephrases what was retrieved. The citation check
decides what the ward lead sees: an answer that cites anything that was not retrieved is replaced by the protocol's
own steps ([ADR-0023](adr/0023-rag-protocol-guidance.md)). Circuit breakers make a failing provider cost no waiting
time: a failing writer falls back to the protocol's steps at once, and a failing embedder gives 503 at once.
