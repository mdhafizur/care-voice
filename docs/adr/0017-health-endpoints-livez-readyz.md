# 17. Health endpoints: `/livez` and `/readyz`

- Status: Accepted
- Date: 2026-10-25

## Context

Kubernetes and the load balancer need two different answers: "is this process broken, so it should be restarted?"
and "should traffic go here right now?". Mixing them up causes restart storms: if liveness checked the database, a
database outage would restart every pod, which fixes nothing.

## Decision

- `GET /livez`: the process is up and serves HTTP. It checks no dependencies. If it fails, Kubernetes restarts the
  pod.
- `GET /readyz`: the critical dependencies are reachable: Postgres for documentation-service; a MongoDB primary and
  the RabbitMQ consumer for alert-service. If it fails, the answer is 503, and the pod is taken out of the load
  balancer, not restarted. A consumer that has paused on purpose during a MongoDB outage still counts as connected;
  the MongoDB check is what reports the outage.
- Non-critical dependencies are reported in the body as `DEGRADED`, without failing readiness: Redis, and RabbitMQ
  for documentation-service (the outbox keeps the events meanwhile).
- Probes run every few seconds, so a healthy, fast dependency check is logged only at TRACE. A failing check, or one
  that takes 500 ms or more, is logged at DEBUG, and a change of state is logged once at INFO or WARN.
- On SIGTERM, readiness turns 503 first, and then the pod drains (ADR-0013).
- The names follow the Kubernetes API server's own endpoints. No authentication; excluded from access logs and
  traces.

## Consequences

- A database outage takes pods out of rotation without killing them, and they come back on their own.
- Uptime Kuma checks both readiness endpoints from outside.

## Alternatives considered

- **A single `/health`**: cannot say "alive but not ready".
- **`/health/live`, `/health/ready`**: equivalent; `/livez` and `/readyz` follow the Kubernetes convention.

Which dependency makes a service not ready, for each kind of outage: [docs/availability.md](../availability.md),
section 3.
