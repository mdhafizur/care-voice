# 14. Redis for state shared between pods, Caffeine as the local cache

- Status: Accepted
- Date: 2026-10-25

## Context

Every service runs as several pods (ADR-0013). Memory inside one pod is then not enough for three things: rate
limits, cache invalidation, and pushing new observations to SSE clients that are connected to a different pod. Reads
of residents and ward queries happen often and are cheap to cache.

## Decision

- **Caffeine** is the first-level (L1) cache inside each pod. It is cache-aside: the `CachingResidentRepository`
  decorator caches residents for 5 minutes, and ward query pages are cached for 10 seconds. The cache is never the
  source of truth.
- **Redis** (Lettuce client) is used for coordination, not as a data store:
  - rate-limit counters, updated by an atomic Lua script, so the limit holds across all pods;
  - cache invalidation messages on pub/sub after each commit, so every pod drops stale entries;
  - SSE fan-out: every pod learns about new observations and pushes them to its own clients.
- Redis never stores personal data or decrypted values.
- **Graceful degradation** when Redis is down:
  - Rate limiting fails open with a warning: a nurse who can document matters more than a strict limit. Login keeps
    an in-memory limit per pod as a floor, so password guessing stays limited.
  - Caches rely on their TTLs alone, so stale data is bounded by them.
  - SSE sends only the observations of the local pod.
  - `/readyz` stays up but reports `redis: DEGRADED`.
- On top of this, HTTP caching: ETag + `If-None-Match` → 304 (ADR-0011).
- High availability: the Helm chart's default `REDIS_URL` is a Sentinel URL (`redis-sentinel://...`, primary +
  replica + 3 Sentinels). docker compose and the local k3d cluster run a single Redis.

## Consequences

- Frequent reads come from memory, and behaviour across pods is correct.
- Pub/sub is fire-and-forget. A pod that misses an invalidation serves stale data until the TTL ends; that is why
  the TTLs are short.
- One more system to run, but it is not on the critical path.

## Alternatives considered

- **Redis as the only cache**: a network round trip on every read, and Redis would hold personal data.
- **No shared state**: rate limits per pod multiply with the number of replicas, and SSE clients miss events from
  other pods.
