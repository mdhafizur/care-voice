package com.carevoice.alert.infrastructure.amqp

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel

/**
 * The RabbitMQ topology alert-service consumes from (docs/CONTRACTS.md, section 8a). Declarations are idempotent, so
 * every pod declares on startup and after a reconnect; declaring the exchanges too (the publisher also declares them)
 * keeps the consumer startable before documentation-service ever ran.
 *
 * Why quorum queues: they replicate across the cluster nodes with Raft, so a broker node failure loses no accepted
 * message, and they support delivery limits and at-least-once dead-lettering. Classic mirrored queues are removed in
 * RabbitMQ 4.
 *
 * Retry without a delay plugin: a rejected message is dead-lettered to the retry exchange, waits in
 * [RETRY_QUEUE] for [RETRY_DELAY_MS] (message TTL) and is dead-lettered back to [EVENTS_EXCHANGE]. It returns with a
 * queue-specific routing key ([RETRY_ROUTING_KEY]) bound only to [QUEUE]: re-publishing with the original
 * `observation.recorded.v1` key would deliver the retry to every other service bound to the topic as well.
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
    const val RETRY_DELAY_MS = 5_000

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

        channel.queueDeclare(
            RETRY_QUEUE,
            true,
            false,
            false,
            mapOf<String, Any>(
                "x-queue-type" to "quorum",
                "x-message-ttl" to RETRY_DELAY_MS,
                "x-dead-letter-exchange" to EVENTS_EXCHANGE,
                "x-dead-letter-routing-key" to RETRY_ROUTING_KEY,
            ),
        )
        channel.queueBind(RETRY_QUEUE, RETRY_EXCHANGE, QUEUE_ROUTING_KEY)

        channel.queueDeclare(DEAD_LETTER_QUEUE, true, false, false, mapOf<String, Any>("x-queue-type" to "quorum"))
        channel.queueBind(DEAD_LETTER_QUEUE, DEAD_LETTER_EXCHANGE, QUEUE_ROUTING_KEY)
    }
}
