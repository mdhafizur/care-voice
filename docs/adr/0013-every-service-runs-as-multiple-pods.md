# 13. Every service runs as two or more pods

- Status: Accepted
- Date: 2026-10-25

## Context

With one instance, every deploy means downtime, and one crash takes the service down. Code written for one instance
quietly breaks with two: jobs run twice, in-memory caches go stale, rate limits multiply, and server-sent event
(SSE) clients miss events that happened on the other pod.

## Decision

No code may assume it is the only instance. Each concern has a rule:

| Concern | Rule |
|---|---|
| Scheduled jobs, seeder | run only while holding `pg_try_advisory_xact_lock`; inserts use `ON CONFLICT DO NOTHING` |
| Migrations | Flyway's lock and the Mongo migration lock; in Kubernetes a single Job before each install or upgrade |
| Outbox relay | one per pod; `SKIP LOCKED` and a lease mean no row is published twice at the same time |
| Broker consumers | competing consumers on one quorum queue; a pod that loses MongoDB pauses only itself |
| Concurrent updates | optimistic locking, never read-then-write without a version |
| Caches | Caffeine per pod, invalidated through Redis pub/sub; no cache is a source of truth |
| Rate limits | shared counters in Redis |
| SSE | Redis sends each event to every pod; `Last-Event-ID` replays missed events from Postgres, so no sticky sessions |
| Shutdown | readiness goes 503 first, then the pod finishes requests, closes SSE streams, finishes the current outbox batch and stops the AMQP consumer after its in-flight messages |
| Metrics | counters are summed across pods (`sum()`), shared-state gauges such as outbox lag use `max()` |

docker compose runs 2 replicas of each service behind Traefik. Kubernetes runs 2 to 6 per service with a
HorizontalPodAutoscaler, a PodDisruptionBudget and topology spread.

## Consequences

- Rolling deploys and losing a node cause no downtime.
- Every rule needs a test with two instances on one database, Redis or broker, where practical.
- Redis becomes a dependency (ADR-0014), designed so the service degrades instead of failing when Redis is down.

## Alternatives considered

- **Leader election** (Kubernetes Lease) for jobs: works, but advisory locks need no extra component.
- **Sticky sessions** for SSE: ties users to one pod and breaks on every pod drain.

What replicas buy and cost, and how each dependency outage is handled: [docs/availability.md](../availability.md).
