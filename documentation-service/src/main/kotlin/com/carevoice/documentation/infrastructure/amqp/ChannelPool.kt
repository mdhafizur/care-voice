package com.carevoice.documentation.infrastructure.amqp

import com.carevoice.documentation.outbox.PublishResult
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ReturnCallback
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * A small pool of confirm-mode channels for [AmqpEventPublisher]. A RabbitMQ [Channel] must not be used by two threads
 * at once, so each publish takes one channel for its whole publish-and-confirm and gives it back afterwards. At most
 * [maxIdle] channels wait here for reuse; the rest are closed.
 */
internal class ChannelPool(private val maxIdle: Int) {
    private val idle = ConcurrentLinkedDeque<PooledChannel>()

    /** An open idle channel, or null when none is left. Closed channels found on the way are thrown away. */
    fun takeIdle(): PooledChannel? {
        while (true) {
            val candidate = idle.pollFirst() ?: return null
            if (candidate.channel.isOpen) return candidate
            candidate.closeQuietly()
        }
    }

    /**
     * Keeps [pooled] for reuse when [keep] is true, it is still open and the pool has room; otherwise closes it.
     * The publisher passes `keep = false` for a channel that saw an error, a timeout or a nack, because its confirm
     * bookkeeping may be out of step, and once the publisher is closed.
     */
    fun giveBack(pooled: PooledChannel, keep: Boolean) {
        if (keep && pooled.channel.isOpen && idle.size < maxIdle) idle.offerFirst(pooled) else pooled.closeQuietly()
    }

    /** Closes every idle channel; used when the publisher shuts down. */
    fun closeAll() {
        generateSequence { idle.pollFirst() }.forEach { it.closeQuietly() }
    }
}

/**
 * One channel in confirm mode plus the return listener that reports unroutable messages. Used by one coroutine
 * at a time (borrowed from the pool), which is what makes the per-channel [returned] slot safe.
 */
internal class PooledChannel private constructor(val channel: Channel) {
    private val returned = AtomicReference<String?>(null)

    init {
        channel.addReturnListener(
            ReturnCallback { ret ->
                // replyCode is an AMQP reply code, here usually 312 NO_ROUTE: no queue is bound for this
                // routing key. Official list: https://www.rabbitmq.com/amqp-0-9-1-reference.html
                returned.set("unroutable: ${ret.replyCode} ${ret.replyText}")
            },
        )
        channel.confirmSelect()
    }

    /**
     * Publishes with `mandatory = true` and waits up to [timeout] for the broker's ack.
     * Published only when the broker acked and did not hand the message back as unroutable.
     */
    fun publishAndConfirm(message: AmqpMessage, timeout: Duration): PublishResult {
        returned.set(null)
        // The `true` is the `mandatory` flag: an unroutable message comes back with `basic.return` (see the
        // AmqpEventPublisher class comment) instead of being dropped.
        channel.basicPublish(message.exchange, message.routingKey, true, message.properties, message.body)
        val acked =
            try {
                channel.waitForConfirms(timeout.inWholeMilliseconds)
            } catch (_: TimeoutException) {
                return PublishResult.Retryable("confirm timeout after $timeout")
            }
        returned.get()?.let { return PublishResult.Retryable(it) }
        return if (acked) PublishResult.Published else PublishResult.Retryable("nacked by broker")
    }

    /**
     * `abort` rather than `close`: the channel may have unconfirmed messages or a broker that stopped answering,
     * and abort never throws and does not wait for outstanding confirms.
     */
    fun closeQuietly() {
        runCatching { channel.abort() }
    }

    companion object {
        /** Opens a new channel and switches it to confirm mode with the return listener. */
        fun open(connection: Connection): PooledChannel = PooledChannel(connection.createChannel())
    }
}
