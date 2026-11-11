# Availability: replicas, service boundaries, dependency outages

How CareVoice keeps working when parts of it fail, and why it is built this way. This page answers three questions:

1. [Why every service runs as several pods](#1-why-several-pods-replicas)
2. [Why two services instead of one monolith](#2-why-two-services-instead-of-one-monolith)
3. [What happens when Postgres, MongoDB, RabbitMQ, Redis or an AI provider is down](#3-dependency-outages)

The first two are separate decisions that are easy to mix up. **Replicas** are copies of the *same* service.
**Service boundaries** decide how the code is *split*. A monolith can run as three pods, and a microservice as one.
CareVoice does both: two services, each with at least two replicas.

Related decisions: [ADR-0003](adr/0003-two-services-hexagonal-architecture.md) (two services),
[ADR-0006](adr/0006-transactional-outbox.md) (outbox), [ADR-0013](adr/0013-every-service-runs-as-multiple-pods.md)
(several pods), [ADR-0017](adr/0017-health-endpoints-livez-readyz.md) (`/livez` and `/readyz`),
[ADR-0026](adr/0026-postgres-backups-wal-archiving.md) (backups).

## 1. Why several pods (replicas)

**Not to save resources: replicas cost a little more.** Every JVM pod needs its own base memory, so two small pods
use more than one. The extra cost buys availability, deployments without downtime, and capacity that follows the
load.

| Reason | What it means here |
|---|---|
| **Failures** | A crashed pod or a dead node does not take the service down. The other replica keeps serving while Kubernetes replaces the lost one. The PodDisruptionBudget (`minAvailable: 1`) keeps one pod running even while a node is drained for maintenance. |
| **Deployments** | A rolling update starts a new pod, waits until its `/readyz` is green, and only then stops an old one. When a pod shuts down, it first reports "not ready", so the load balancer stops sending it traffic before its open requests finish. With one pod, every release would be a short outage. |
| **Load** | The HorizontalPodAutoscaler runs each service with 2 to 6 pods, based on CPU (target 70 %). The morning documentation round is the peak. This is horizontal scaling: no ever-bigger single machine, which has a hard limit and still fails as one unit. You pay for peak capacity only while the peak lasts. |
| **Design discipline** | When any pod may get any request, no state may live in one pod's memory. Shared state is in Postgres and Redis; work that must happen only once is coordinated in the database. |

What "no pod is special" means in this code (CONTRACTS section 9b):

- Rate limits and cache invalidation go through Redis, so a limit holds no matter how many pods serve a user.
- Live updates (SSE) reach the right pod through Redis pub/sub: the pod that holds the client's connection. A client
  that reconnects with `Last-Event-ID` gets the observations it missed from Postgres, so no sticky sessions are
  needed.
- The outbox relay claims rows with `FOR UPDATE SKIP LOCKED`, so two pods never publish the same event.
- The transcript retention job and the demo seeder take a Postgres advisory lock, so only one pod runs them.
- Unique constraints decide idempotency, so two pods that get the same request at the same moment cannot both store
  it.

**In short:** replicas trade a bit of memory for surviving crashes, node maintenance and deployments, and for scaling
with demand. They also force the pods to be stateless, which is what makes the rest possible.

## 2. Why two services instead of one monolith

This is a different decision, with different reasons. **A modular monolith is a perfectly good default**, and for a
small team the better one. The split here is justified only by the points below; ADR-0003 says the same.

| Reason | What it means here |
|---|---|
| **Failure isolation** | If alert-service is down, nurses keep documenting: events wait in the outbox and in RabbitMQ and are processed when it comes back. In a monolith, a bug in alerting can take documentation down with it. |
| **Different data and load** | Documentation is write-heavy, relational and transactional (Postgres). Alerts are read-heavy, document-shaped and event-driven (MongoDB). Each stores data and scales in its own way. |
| **Independent releases** | A change to an alert rule does not redeploy the documentation path that nurses depend on. |

The cost, which belongs in every honest answer:

- network calls instead of function calls, each needing timeouts and retries;
- eventual consistency, which is why there is an outbox, idempotency keys and dead-letter handling;
- two databases to run, back up and monitor;
- distributed tracing just to follow one request across both services.

**In short:** split only where failure isolation or really different data access pays for the extra moving parts.
Otherwise, start with a modular monolith.

## 3. Dependency outages

### The rules behind every case

Each dependency is judged by two questions. Is it on the critical path (can a nurse still document without it)? And
can the work wait (is there a durable buffer)? Then three rules hold for all of them:

1. **Liveness never checks dependencies; readiness does.** `/livez` only says the process is alive, so a database
   outage never makes Kubernetes restart healthy pods in a loop. `/readyz` says "send me traffic" and turns red only
   for critical dependencies.
2. **Every remote call has a time limit; the important ones also have a circuit breaker.** A dead dependency fails
   fast instead of tying up threads. Each request also has an overall deadline (`REQUEST_TIMEOUT_MS`, 30 s by
   default, then 503).
3. **Every retry is safe.** Idempotency keys and unique constraints make repeating an operation harmless.

### Overview

| Outage | Class | Can a nurse document? | Alerts | Data lost? |
|---|---|---|---|---|
| Postgres | critical (documentation) | no: 503, safe to retry | delayed | no |
| MongoDB | critical (alerts only) | yes | delayed; the consumer pauses ([long outages](#long-mongodb-outages)) | no |
| RabbitMQ | degradable | yes | delayed | no |
| Redis | degradable | yes | not affected | no |
| AI provider | degradable | yes (rules only) | not affected | no |

### Postgres down (documentation-service): critical

- `/readyz` returns 503, the load balancer stops routing to the pod, and writes fail with 503 instead of hanging.
- A transcript, its observations, its outbox rows and its audit row are one transaction: all or nothing, never half
  saved.
- The client retries with the same `Idempotency-Key`. The unique constraint makes sure the transcript is stored once,
  and the retry gets the original response back.
- A statement that runs longer than 5 s is stopped by Postgres (`statement_timeout`) and answered with 503, so a slow
  database cannot hold requests forever.
- A read whose connection was lost (for example during a failover) is tried once more on a new connection. Writes are
  never retried this way, because the commit may already have happened.
- In Kubernetes, CloudNativePG runs two instances and promotes the standby automatically. The services connect
  through PgBouncer (`carevoice-pg-pooler-rw`), which always points at the current primary, so nothing needs to be
  reconfigured after a failover.
- Read replica down: reads fall back to the primary. This is logged once and counted in
  `carevoice_db_read_fallback_total`.

### Postgres data lost or damaged: restore from backups (Kubernetes)

The replica protects against a lost instance. It does not protect against a bad migration, a wrong `DELETE` or losing
the whole cluster, because those reach the replica too. For these cases, every WAL segment is archived to object
storage and a base backup is taken every night (ADR-0026), so the database can be restored to any second of the last
14 days.

- Data lost at most: about 5 minutes of writes (`archive_timeout`) if the cluster and its volumes are gone; nothing
  if only one instance is lost.
- How to restore: create a new cluster from the object store with `recoveryTarget.targetTime` (the drill script shows
  the manifest), check it, then point `DATABASE_URL` or the Pooler at it. Never restore over the damaged cluster.
- Proof: `make k8s-pg-restore-drill` restores to a moment between two writes and checks that only the first one
  survived.
- Alerts: `PostgresWalArchivingFailing` (critical) and `PostgresWalArchiveBacklog` (warning) in Grafana → Alerting.

### RabbitMQ down: degraded, nothing lost

- Nurses keep documenting. Events are written to the Postgres outbox in the same transaction; they are not sent to
  RabbitMQ directly.
- The outbox relay retries with growing, randomised waits. Its circuit breaker stops calling a dead broker, and rows
  that wait while the breaker is open do not use up attempts. A row becomes `DEAD` only after 10 attempts *and*
  24 hours, so an outage shorter than a day never marks a valid event `DEAD`.
- When the broker is back, the backlog drains by itself. `carevoice_outbox_pending` and the outbox lag show the
  backlog the whole time.
- The alert-service consumer reconnects by itself, and messages already in the queue stay there. Quorum queues on a
  three-node cluster also survive the loss of one RabbitMQ node.
- The cost: alerts arrive late, but never twice in effect. Delivery is at-least-once, and the consumer ignores
  duplicates (`processed_events` and the unique `{eventId, rule}` index).

### Redis down (documentation-service): degraded by design

- Rate limits fail open, with a WARN log and a metric (`carevoice_ratelimit_fail_open_total`). Login keeps an
  in-memory limit per IP address as a floor, so guessing passwords stays limited.
- Caches fall back to their short expiry times instead of explicit invalidation.
- Live updates reach only the clients connected to the publishing pod. A client that reconnects gets the missed
  observations from Postgres.
- Readiness stays UP; `/readyz` reports `redis: DEGRADED` in its body.
- Locally Redis is a single instance; the production Helm values use Sentinel. Either way, nothing whose correctness
  matters depends on Redis.

### MongoDB down (alert-service): critical for alerts only

- `/readyz` of alert-service returns 503, and its API answers 503 within about 3 seconds (the driver's server
  selection timeout) instead of hanging.
- Events wait in RabbitMQ: the consumer acknowledges a message only after the alert is written.
- MongoDB runs as a three-member replica set. A new primary is elected in about 10 s, and the driver retries the
  write after the election. Writes wait for a majority, and decisions read majority-committed data, so a failover
  cannot lose an alert or bring back an old one.
- Documentation is not affected: different service, different database.

### Long MongoDB outages

The rule the outbox relay follows applies here too: a dependency outage must not use up a message's retries.

- **Outage → pause.** When a write fails and the Mongo health check confirms that MongoDB is down, the consumer
  cancels its subscription and puts the messages it holds back in the queue, without counting an attempt. It checks
  MongoDB every 5 s and subscribes again once it is healthy. Meanwhile the events wait in the replicated queue
  (`carevoice_amqp_consumer_paused` = 1). An outage of any length loses nothing and dead-letters nothing.
- **Short blip → growing waits.** If MongoDB answers again right away, the event is retried after 5 s, 30 s, 2 min,
  10 min and 30 min (one retry queue per wait). Only when delivery 6 fails, about 43 minutes later, does the event
  go to the dead-letter queue.
- **Replay.** `make dlq-replay` (`alert-service replay-dead-letters [limit]`) sends dead-lettered events back to the
  queue with a fresh set of retries, once the cause is fixed.

### AI provider down: degraded by design

- Extraction: the rules run first, and the AI (Claude or Gemini) is asked only when they find nothing. A timeout, an
  error or an open circuit breaker just means "no AI result"; the nurse's request still succeeds.
- Guidance: a failing writer falls back to the protocol's own steps. A failing embedder (Voyage or Gemini) means no
  search is possible, so the guidance endpoint answers 503. Circuit breakers make both answers immediate instead of
  waiting 15–20 s for the provider every time.

## 4. Summary

**Replicas versus one pod:** replicas are not cheaper; they cost a little memory. In return, a crash, a node drain or
a deployment never takes a service down, the autoscaler follows the morning peak, and the pods have to be stateless:
shared state lives in Postgres and Redis, and background work is coordinated with locks.

**Two services versus a monolith:** a separate trade-off. Documentation must keep working when alerting fails, and
the two have different data shapes. The price is eventual consistency and more to operate, so a split is only worth
it where those reasons hold.

**Outages:** every dependency is either critical or degradable. Critical ones (Postgres for documentation, MongoDB
for alerts) make the pod not ready, while liveness ignores dependencies, so there is no restart storm. Both run with
automatic failover, and Postgres can also be restored from backups. RabbitMQ is off the nurse's critical path: the
outbox keeps the events in Postgres and sends them when the broker is back, so alerts are delayed, never lost, and
duplicates are ignored. Redis and the AI providers are optional: rate limits fail open with a floor on login, caches
fall back to expiry times, and documentation works with the rules alone. The worst case for a nurse is a short 503
and a safe retry, never lost or duplicated data.
