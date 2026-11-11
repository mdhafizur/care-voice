# 7. RabbitMQ as the event transport, HTTP as an alternative adapter

- Status: Accepted
- Date: 2026-10-25

## Context

The outbox relay needs a way to get events to alert-service. A direct HTTP call ties the two services together:
when alert-service is down or slow, the sender is affected, and the receiver has no buffer and no natural
backpressure.

## Decision

- **Publish through an `EventPublisher` port.** `EVENT_TRANSPORT` selects `AmqpEventPublisher` (the default) or the
  HTTP `AlertServiceClient`. Both deliver the same `ObservationRecordedEvent`, and alert-service handles both with
  the same use case.
- **Topology**:
  - a topic exchange `carevoice.events`;
  - a **quorum queue** `alert-service.observations` (replicated with Raft, with `x-delivery-limit`);
  - retry queues with growing waits (5 s, 30 s, 2 min, 10 min, 30 min). When a message's wait is over, it goes back
    with a routing key that is bound only to this consumer's queue, so other subscribers do not get it again;
  - a dead-letter exchange `carevoice.events.dlx` with the queue `alert-service.observations.dlq` as the parking lot.
    `alert-service replay-dead-letters` (`make dlq-replay`) moves its messages back.
- **Publisher confirms and `mandatory=true`**: the outbox row is marked published only after the broker has
  confirmed that it stored the message in a queue.
- **Consumer**:
  - manual acks, and `basicQos(prefetch)` so the broker sends only a limited number of unacknowledged messages;
  - the ack is sent only after the Mongo write succeeded;
  - a short failure goes to the next retry queue; when delivery 6 fails (about 43 minutes after the first), the
    message goes to the dead-letter queue;
  - when MongoDB is really down, the consumer pauses: it stops consuming, puts its messages back in the queue without
    counting an attempt, and starts again when MongoDB is healthy;
  - invalid JSON or an unknown schema version goes to the dead-letter queue at once.
- **Competing consumers across pods**, with no assumption about order (each event is independent and idempotent).
- **A 3-node cluster in Kubernetes** (one node in docker compose).

## Consequences

- alert-service can be slow, scaled down or redeployed without affecting nurses; the queue absorbs bursts.
- Retries and dead letters are visible as queues and metrics (depth, unacknowledged, dead-letter queue size). In
  Kubernetes the alert `AlertEventsDeadLettered` fires when the dead-letter queue is not empty.
- One more stateful system to run. Pact message contracts keep the payload stable.

## Alternatives considered

- **Kafka**: better for replayable event logs and very high throughput, but heavier to run, and retries and dead
  letters per message must be built on top. RabbitMQ fits task-style delivery with retries.
- **HTTP only**: kept as an adapter for demos; not the default.
