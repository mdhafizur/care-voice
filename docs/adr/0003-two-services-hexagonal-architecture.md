# 3. Two services with a hexagonal architecture and a pure domain module

- Status: Accepted
- Date: 2026-10-25

## Context

The product has two clearly different jobs:

- Turn a nurse's transcript into checked, stored vital signs. This side writes a lot and must never lose or corrupt
  data.
- Turn recorded vital signs into alerts for the ward lead. This side reacts to events and only stores data derived
  from them.

The demo must also stay small enough to explain.

## Decision

- **Two deployable services**: `documentation-service` owns transcripts and observations, `alert-service` owns
  alerts. They share only the event contract in the `contracts` module, never a database.
- **A `domain` module in pure Kotlin** (no Ktor, JDBC or Mongo imports). It holds parsing, validation (smart
  constructors, value classes, `Either`) and the review rules. Both services depend on it; its only dependency is
  Arrow's core library for `Either` (ADR-0027).
- **Hexagonal layers inside each service**: `api` → `application` (use cases and ports) → `infrastructure`
  (adapters). A port is an interface the use case needs; an adapter implements it for one technology. Adapters can be
  swapped behind their ports (Repository, Adapter and Decorator patterns).
- **The rules are checked, not hoped for**: `architecture-test` runs Konsist checks in every build. For example,
  driver libraries may only be imported in `infrastructure`, and `api` never touches `infrastructure`.

## Consequences

- The domain is tested in milliseconds without databases (about 400 tests), including exhaustive range checks.
- Swapping the event transport (HTTP or RabbitMQ, ADR-0007) or adding a cache (a Decorator) touches one adapter.
- Two services bring network calls, eventual consistency and contract tests (Pact). This is on purpose: these are
  exactly the problems the demo is meant to show solutions for.

## Alternatives considered

- **One modular monolith**: simpler to run, and the right first step for a real product of this size. Not chosen for
  the demo, because it hides the distributed-systems topics (outbox, idempotency, retries).
- **More services** (auth, residents, notifications): more to run and explain, without new ideas.

What replicas and service boundaries buy and cost: [docs/availability.md](../availability.md).
