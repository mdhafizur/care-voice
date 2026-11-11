# 6. Transactional outbox for publishing events

- Status: Accepted
- Date: 2026-10-25

## Context

After an observation is saved, alert-service must hear about it. Writing to the database and sending a message
touch two different systems, so the simple approach fails in two ways:

- Commit, then the publish fails (crash, broker down): the observation exists but the alert never comes. That could
  be a missed hypertensive crisis.
- Publish, then the commit fails: an alert for an observation that does not exist.

A distributed transaction across Postgres and RabbitMQ (two-phase commit) is slow and fragile, so it is not an
option.

## Decision

Use a transactional outbox:

1. In the same transaction as the observations, insert one `outbox` row per event: status `PENDING`, the payload,
   `request_id` and `traceparent` (so logs and traces connect to the nurse's request later).
2. A relay runs in every pod. It claims up to 50 due rows with `FOR UPDATE SKIP LOCKED`, moves their
   `next_attempt_at` forward by a 30 s lease (so another pod retries them if this pod crashes), commits, and then
   publishes outside the transaction.
3. A row becomes `PUBLISHED` only after the broker confirms it. Failures are retried with exponential backoff and
   full jitter (base 500 ms, cap 60 s). A retryable failure marks a row `DEAD` only when it has used 10 attempts
   AND is older than 24 hours (`retryFor`), so an outage never kills valid events. A non-retryable 4xx answer marks
   it `DEAD` at once.
4. A Resilience4j circuit breaker stops calls to a failing downstream. While it is open, rows are rescheduled
   without counting an attempt.
5. Backpressure: claimed rows go through a bounded channel (100 rows) to 8 parallel publishers. When the channel is
   full, the claimer waits instead of claiming more.

## Consequences

- No lost events: if the transaction committed, the event is published sooner or later.
- **At-least-once, not exactly-once**: a crash after publishing but before marking the row can publish an event
  twice. So every consumer must ignore duplicates by `eventId` (ADR-0008).
- Nurses keep documenting while the broker or alert-service is down; the outbox buffers the events. The number of
  pending rows and the outbox lag are metrics on the Grafana dashboard.
- Events arrive with a small delay (the relay polls).
- Published rows stay in the table. Nothing deletes them yet, so the table needs a cleanup job.

## Alternatives considered

- **Publish after commit, best effort**: loses events on a crash.
- **Change data capture (Debezium)**: no polling, but adds Kafka Connect to run.
- **LISTEN/NOTIFY**: not reliable delivery, and it does not work with PgBouncer in transaction mode (ADR-0016).
