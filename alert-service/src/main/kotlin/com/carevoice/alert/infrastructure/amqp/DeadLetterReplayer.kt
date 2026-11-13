package com.carevoice.alert.infrastructure.amqp

import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import com.rabbitmq.client.ConnectionFactory
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Moves dead-lettered events from [AmqpTopology.DEAD_LETTER_QUEUE] back to alert-service's own queue, once the cause
 * (a long Mongo outage, a bug fixed by a deploy) is gone. Run as a one-shot command: `alert-service replay-dead-letters
 * [limit]` (`make dlq-replay`).
 *
 * Each message is re-published to [AmqpTopology.EVENTS_EXCHANGE] with [AmqpTopology.RETRY_ROUTING_KEY], which is bound
 * only to [AmqpTopology.QUEUE], so other services bound to the topic never see it twice. Its retry state is reset
 * (attempt header, death history, dead-letter reason), so it gets the full set of retries again, and
 * [HEADER_REPLAYED] counts how often it was replayed. The original is acked only after the broker confirmed the copy;
 * a crash in between leaves a duplicate, which the consumer ignores (idempotent on `eventId`).
 *
 * At most the number of messages present at the start is replayed, so the command always ends. A replayed event that
 * still fails goes through the retry tiers again (minutes), not straight back into the dead-letter queue.
 */
class DeadLetterReplayer(private val connectionFactory: ConnectionFactory) {
    private val log = LoggerFactory.getLogger(DeadLetterReplayer::class.java)

    data class Result(val replayed: Int, val remaining: Int)

    /**
     * Moves up to [limit] messages from the dead-letter queue back to the main queue.
     * Returns how many were moved and how many are still in the dead-letter queue.
     */
    fun replay(limit: Int = Int.MAX_VALUE): Result {
        require(limit >= 1) { "limit must be >= 1 but was $limit" }
        connectionFactory.newConnection("alert-service-dlq-replay").use { connection ->
            val channel = connection.createChannel()
            // Makes sure the binding that routes RETRY_ROUTING_KEY to the queue exists before anything is moved.
            AmqpTopology.declare(channel)
            channel.confirmSelect()
            val budget = minOf(limit, channel.queueDeclarePassive(AmqpTopology.DEAD_LETTER_QUEUE).messageCount)
            var replayed = 0
            while (replayed < budget) {
                val message = channel.basicGet(AmqpTopology.DEAD_LETTER_QUEUE, false) ?: break
                val headers = message.props.headers.orEmpty()
                val properties = message.props.builder().headers(replayHeaders(headers)).build()
                channel.basicPublish(
                    AmqpTopology.EVENTS_EXCHANGE,
                    AmqpTopology.RETRY_ROUTING_KEY,
                    false,
                    properties,
                    message.body,
                )
                channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS)
                channel.basicAck(message.envelope.deliveryTag, false)
                replayed++
                log.event(
                    Level.INFO,
                    LogType.EXTERNAL_REQUEST,
                    "Dead-lettered event replayed",
                    "eventId" to message.props.messageId,
                    "reason" to headers[ObservationEventConsumer.HEADER_DEAD_LETTER_REASON]?.toString()?.take(200),
                )
            }
            val remaining = channel.queueDeclarePassive(AmqpTopology.DEAD_LETTER_QUEUE).messageCount
            log.event(
                Level.INFO,
                LogType.EXTERNAL_REQUEST,
                "Dead-letter replay finished",
                "replayed" to replayed,
                "remaining" to remaining,
            )
            return Result(replayed, remaining)
        }
    }

    companion object {
        /** How often an event was replayed from the dead-letter queue. */
        const val HEADER_REPLAYED = "x-carevoice-replayed"

        private const val CONFIRM_TIMEOUT_MS = 5_000L

        /** Headers for the replayed copy: retry state and dead-letter details removed, replay count increased. */
        fun replayHeaders(headers: Map<String, Any?>): Map<String, Any?> {
            val previous = (headers[HEADER_REPLAYED] as? Number)?.toInt() ?: 0
            return ObservationEventConsumer.withoutDeathHistory(headers)
                .filterKeys {
                    it != ObservationEventConsumer.HEADER_ATTEMPT &&
                        it != ObservationEventConsumer.HEADER_DEAD_LETTER_REASON &&
                        it != ObservationEventConsumer.HEADER_ORIGINAL_ROUTING_KEY
                } + (HEADER_REPLAYED to previous + 1)
        }
    }
}
