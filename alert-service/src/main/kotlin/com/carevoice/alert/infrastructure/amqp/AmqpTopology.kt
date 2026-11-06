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

    const val BINDING_PATTERN = "observation.recorded.*"

    /** Routing key of messages going from [QUEUE] to [RETRY_QUEUE] and of poison messages sent to the DLX. */
    const val QUEUE_ROUTING_KEY = QUEUE

    /** Routing key of messages returning from [RETRY_QUEUE]; matches no `observation.recorded.*` binding. */
    const val RETRY_ROUTING_KEY = "retry.$QUEUE"

    const val DELIVERY_LIMIT = 10

    /** Wait before delivery n + 1 after delivery n failed; once all are used the event is dead-lettered. */
    val RETRY_DELAYS: List<Duration> = listOf(5.seconds, 30.seconds, 2.minutes, 10.minutes, 30.minutes)

    /** Retry tier (0-based index into [RETRY_DELAYS]) to use after delivery [attempt] (1-based) failed. */
    fun retryTier(attempt: Int): Int = (attempt - 1).coerceIn(0, RETRY_DELAYS.lastIndex)

    fun retryQueue(tier: Int): String = if (tier == 0) RETRY_QUEUE else "$RETRY_QUEUE.${tierSuffix(tier)}"

    fun retryRoutingKey(tier: Int): String =
        if (tier == 0) QUEUE_ROUTING_KEY else "$QUEUE_ROUTING_KEY.retry.${tierSuffix(tier)}"

    private fun tierSuffix(tier: Int): String = "${RETRY_DELAYS[tier].inWholeSeconds}s"

    fun declare(channel: Channel) {
        channel.exchangeDeclare(EVENTS_EXCHANGE, BuiltinExchangeType.TOPIC, true)
        channel.exchangeDeclare(RETRY_EXCHANGE, BuiltinExchangeType.DIRECT, true)
        channel.exchangeDeclare(DEAD_LETTER_EXCHANGE, BuiltinExchangeType.DIRECT, true)

        channel.queueDeclare(
            QUEUE,
            true,
            false,
            false,
            mapOf<String, Any>(
                "x-queue-type" to "quorum",
                "x-delivery-limit" to DELIVERY_LIMIT,
                "x-dead-letter-exchange" to RETRY_EXCHANGE,
                "x-dead-letter-routing-key" to QUEUE_ROUTING_KEY,
            ),
        )
        channel.queueBind(QUEUE, EVENTS_EXCHANGE, BINDING_PATTERN)
        channel.queueBind(QUEUE, EVENTS_EXCHANGE, RETRY_ROUTING_KEY)

        RETRY_DELAYS.forEachIndexed { tier, delay ->
            channel.queueDeclare(
                retryQueue(tier),
                true,
                false,
                false,
                mapOf<String, Any>(
                    "x-queue-type" to "quorum",
                    "x-message-ttl" to delay.inWholeMilliseconds.toInt(),
                    "x-dead-letter-exchange" to EVENTS_EXCHANGE,
                    "x-dead-letter-routing-key" to RETRY_ROUTING_KEY,
                ),
            )
            channel.queueBind(retryQueue(tier), RETRY_EXCHANGE, retryRoutingKey(tier))
        }

        channel.queueDeclare(DEAD_LETTER_QUEUE, true, false, false, mapOf<String, Any>("x-queue-type" to "quorum"))
        channel.queueBind(DEAD_LETTER_QUEUE, DEAD_LETTER_EXCHANGE, QUEUE_ROUTING_KEY)
    }
}
