package com.carevoice.alert.infrastructure.amqp

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The RabbitMQ topology alert-service consumes from (docs/CONTRACTS.md, section 8a). Declarations are idempotent, so
 * every pod declares on startup and after a reconnect; declaring the exchanges too (the publisher also declares them)
 * keeps the consumer startable before documentation-service ever ran.
 *
 * Why quorum queues: they replicate across the cluster nodes with Raft, so a broker node failure loses no accepted
 * message, and they support delivery limits and at-least-once dead-lettering. Classic mirrored queues are removed in
 * RabbitMQ 4.
 *
 * Retry with growing delays, without a delay plugin: one retry queue per delay in [RETRY_DELAYS] (5 s, 30 s, 2 min,
 * 10 min, 30 min), each with that message TTL. The consumer publishes a failed message to the retry exchange with the
 * routing key of the tier for its attempt ([retryRoutingKey]); when the TTL expires the tier queue dead-letters it
 * back to [EVENTS_EXCHANGE]. It returns with a queue-specific routing key ([RETRY_ROUTING_KEY]) bound only to [QUEUE]:
 * re-publishing with the original `observation.recorded.v1` key would deliver the retry to every other service bound
 * to the topic as well. Together the tiers wait about 43 minutes, long enough to ride out a Mongo failover or a short
 * outage, before the event goes to [DEAD_LETTER_QUEUE] (replayable with `replay-dead-letters`).
 *
 * Why separate queues instead of one queue with per-message TTLs: a queue only expires the message at its head, so a
 * 30-minute message would block the 5-second ones behind it. One TTL per queue keeps every queue FIFO by expiry.
 *
 * The first tier keeps the name and arguments of the original single retry queue ([RETRY_QUEUE], bound with
 * [QUEUE_ROUTING_KEY]): redeclaring an existing queue with different arguments fails, and the main queue's own
 * dead-lettering (delivery limit exceeded, or the consumer's fallback nack) still lands there.
 */
object AmqpTopology {
    const val EVENTS_EXCHANGE = "carevoice.events"
    const val RETRY_EXCHANGE = "carevoice.events.retry"
    const val DEAD_LETTER_EXCHANGE = "carevoice.events.dlx"

    const val QUEUE = "alert-service.observations"
    const val RETRY_QUEUE = "alert-service.observations.retry"
    const val DEAD_LETTER_QUEUE = "alert-service.observations.dlq"

    /**
     * Topic pattern for the events this queue takes. In a topic pattern `*` matches exactly one dot-separated word,
     * here the schema version of the routing key (`observation.recorded.v1`, `observation.recorded.v2`, ...).
     */
    const val BINDING_PATTERN = "observation.recorded.*"

    /** Routing key of messages going from [QUEUE] to [RETRY_QUEUE] and of poison messages sent to the DLX. */
    const val QUEUE_ROUTING_KEY = QUEUE

    /** Routing key of messages returning from [RETRY_QUEUE]; matches no `observation.recorded.*` binding. */
    const val RETRY_ROUTING_KEY = "retry.$QUEUE"

    /**
     * Value of the quorum-queue argument `x-delivery-limit` on [QUEUE]: when one message went back to the queue without
     * an ack more than this many times (for example because a consumer crashed), the broker dead-letters it to
     * [RETRY_EXCHANGE].
     */
    const val DELIVERY_LIMIT = 10

    /** Wait before delivery n + 1 after delivery n failed; once all are used the event is dead-lettered. */
    val RETRY_DELAYS: List<Duration> = listOf(5.seconds, 30.seconds, 2.minutes, 10.minutes, 30.minutes)

    /** Retry tier (0-based index into [RETRY_DELAYS]) to use after delivery [attempt] (1-based) failed. */
    fun retryTier(attempt: Int): Int = (attempt - 1).coerceIn(0, RETRY_DELAYS.lastIndex)

    /** Name of the retry queue for [tier]. Tier 0 keeps the old single retry queue name. */
    fun retryQueue(tier: Int): String = if (tier == 0) RETRY_QUEUE else "$RETRY_QUEUE.${tierSuffix(tier)}"

    /** Routing key for the retry queue of [tier] on [RETRY_EXCHANGE]; tier 0 uses [QUEUE_ROUTING_KEY]. */
    fun retryRoutingKey(tier: Int): String =
        if (tier == 0) QUEUE_ROUTING_KEY else "$QUEUE_ROUTING_KEY.retry.${tierSuffix(tier)}"

    /** Name suffix of a tier: its delay in whole seconds, e.g. "30s". */
    private fun tierSuffix(tier: Int): String = "${RETRY_DELAYS[tier].inWholeSeconds}s"

    /**
     * Declares all exchanges, queues and bindings on [channel].
     * Safe to call on every startup and reconnect: declaring something that already exists with the same
     * arguments changes nothing.
     */
    fun declare(channel: Channel) {
        // Step 1: the three exchanges (an exchange is where messages are published; it routes them to queues).
        // The last argument `true` makes each exchange durable: it survives a broker restart.
        channel.exchangeDeclare(EVENTS_EXCHANGE, BuiltinExchangeType.TOPIC, true)
        channel.exchangeDeclare(RETRY_EXCHANGE, BuiltinExchangeType.DIRECT, true)
        channel.exchangeDeclare(DEAD_LETTER_EXCHANGE, BuiltinExchangeType.DIRECT, true)

        // Step 2: the main queue. A message it gives up on (too many deliveries, or a reject) is dead-lettered
        // (moved on) to the retry exchange. It also gets the events that come back from the retry queues.
        // The three flags after the name: durable = true (the queue survives a broker restart), exclusive = false
        // (other connections, so other pods, may use it), autoDelete = false (it stays when no consumer is left).
        // The `x-...` queue arguments are explained in https://www.rabbitmq.com/docs/queues (optional arguments),
        // https://www.rabbitmq.com/docs/quorum-queues and https://www.rabbitmq.com/docs/dlx (dead lettering).
        channel.queueDeclare(
            QUEUE,
            true,
            false,
            false,
            mapOf<String, Any>(
                // Queue type: a quorum queue (replicated across the broker nodes, see the class comment).
                "x-queue-type" to "quorum",
                // Dead-letter a message that failed delivery more than DELIVERY_LIMIT times.
                "x-delivery-limit" to DELIVERY_LIMIT,
                // Where dead-lettered messages go: the retry exchange ...
                "x-dead-letter-exchange" to RETRY_EXCHANGE,
                // ... with this routing key (without it, the message would keep its own routing key).
                "x-dead-letter-routing-key" to QUEUE_ROUTING_KEY,
            ),
        )
        channel.queueBind(QUEUE, EVENTS_EXCHANGE, BINDING_PATTERN)
        channel.queueBind(QUEUE, EVENTS_EXCHANGE, RETRY_ROUTING_KEY)

        // Step 3: one retry queue per delay. A message waits there for its TTL (time to live), then goes back to
        // the events exchange with the key that only the main queue listens to.
        RETRY_DELAYS.forEachIndexed { tier, delay ->
            // Same flags as the main queue: durable, not exclusive, not auto-deleted.
            channel.queueDeclare(
                retryQueue(tier),
                true,
                false,
                false,
                mapOf<String, Any>(
                    // Queue type: quorum, like the main queue.
                    "x-queue-type" to "quorum",
                    // Message TTL in milliseconds: how long a message waits in this tier
                    // (https://www.rabbitmq.com/docs/ttl).
                    "x-message-ttl" to delay.inWholeMilliseconds.toInt(),
                    // When the TTL is over, the message is dead-lettered back to the events exchange ...
                    "x-dead-letter-exchange" to EVENTS_EXCHANGE,
                    // ... with the routing key that only the main queue is bound to.
                    "x-dead-letter-routing-key" to RETRY_ROUTING_KEY,
                ),
            )
            channel.queueBind(retryQueue(tier), RETRY_EXCHANGE, retryRoutingKey(tier))
        }

        // Step 4: the dead-letter queue, where events end up after all retries are used.
        // Same flags as the other queues; its only argument makes it a quorum queue.
        channel.queueDeclare(DEAD_LETTER_QUEUE, true, false, false, mapOf<String, Any>("x-queue-type" to "quorum"))
        channel.queueBind(DEAD_LETTER_QUEUE, DEAD_LETTER_EXCHANGE, QUEUE_ROUTING_KEY)
    }
}
