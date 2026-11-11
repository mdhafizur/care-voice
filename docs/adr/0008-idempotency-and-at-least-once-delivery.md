# 8. Idempotency everywhere, at-least-once delivery

- Status: Accepted
- Date: 2026-10-25

## Context

Mobile networks lose responses, so the app retries a transcript it may already have sent. The outbox and RabbitMQ
deliver events at least once. Delivering a message exactly once over a network is not possible; making its *effect*
happen exactly once is. An operation is idempotent when doing it twice has the same effect as doing it once.

## Decision

- **Client → documentation-service**: `POST .../transcripts` requires an `Idempotency-Key` header (a UUID). The key
  and the response are saved in the same transaction as the transcript.
  - Same key, same body again: the stored response comes back with `200` and `Idempotent-Replayed: true`.
  - Same key, different body: `409`.
- **Two pods, same key, same moment**: a unique constraint decides. The losing pod catches the violation and
  returns the winner's response. The code never checks first and then inserts.
- **Events**: every event has an `eventId`. It is also the AMQP `message_id` and the HTTP `Idempotency-Key`.
  alert-service records processed ids in `processed_events` (unique index, deleted after 30 days), and alerts have a
  unique `eventId + rule` index. A redelivered event creates nothing new and is simply acknowledged.

## Consequences

- Retries are always safe: for the app, for the relay and for the broker. This is what lets the system use retries
  and backoff freely (ADR-0006, ADR-0007).
- Stored responses and processed-event ids take space; both expire.

## Alternatives considered

- **Exactly-once messaging** (Kafka transactions): covers only the broker, not the database write in alert-service.
- **Removing duplicates by natural key** (resident + time + value): two identical readings a few minutes apart are
  legitimate, so this would drop real data.
