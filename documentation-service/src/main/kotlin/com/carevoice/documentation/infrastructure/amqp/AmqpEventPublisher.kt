package com.carevoice.documentation.infrastructure.amqp

import com.carevoice.documentation.outbox.EventPublisher
import com.carevoice.documentation.outbox.OutboxEvent
import com.carevoice.documentation.outbox.PublishResult
import com.carevoice.documentation.resilience.CircuitBreakerSettings
import com.carevoice.documentation.resilience.CircuitBreakers
import com.carevoice.documentation.resilience.Guarded
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import com.carevoice.documentation.resilience.guard
import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Publishes outbox events to RabbitMQ (docs/CONTRACTS.md, section 8a), the default event transport.
 *
 * Pattern: Adapter (implements the outbox's [EventPublisher] port over AMQP 0-9-1).
 *
 * Delivery guarantees, and why each mechanism is needed:
 * - **Publisher confirms**: `basicPublish` returns as soon as the bytes are in the socket buffer, which says nothing
 *   about whether the broker stored the message. With confirms the broker acks only after it has taken responsibility
 *   (for quorum queues: after a majority of nodes wrote it). The outbox row becomes PUBLISHED only after that ack;
 *   a nack or no answer within [confirmTimeout] is [PublishResult.Retryable].
 * - **`mandatory = true` + return listener**: a confirm alone is not enough. A message that matches no binding (the
 *   consumer's queue was not declared yet, or someone deleted a binding) is silently DROPPED by the exchange and
 *   still confirmed. With `mandatory` the broker hands it back with `basic.return` first, so the relay retries instead
 *   of losing a hypertensive-crisis alert. RabbitMQ sends the return before the ack on the same channel, and the
 *   client dispatches both in order on the connection thread, so the return is known when the confirm wait ends.
 * - **At least once**: a confirm can be lost after the broker stored the message (connection drop), in which case
 *   the row is published again. That is deliberate; alert-service deduplicates on `message_id` = `eventId`.
 *
 * Threading: a RabbitMQ [Channel] must not be used by two threads at once. Each publish borrows a channel from a small
 * pool for its whole publish-and-confirm sequence and returns it afterwards, so the relay's concurrent workers never
 * share one. Channels that saw an error, a timeout or a nack are closed instead of being returned, because their
 * confirm bookkeeping may be out of step. All blocking client calls run on [Dispatchers.IO] via [runInterruptible],
 * so cancelling the publishing coroutine (shutdown) interrupts a confirm wait instead of pinning a thread.
 *
 * Availability: the connection is opened lazily on the first publish, not in the constructor. documentation-service
 * must start and stay ready while the broker is down (nurses keep documenting; the outbox buffers), so an unreachable
 * broker is just a [PublishResult.Retryable]. After the first successful connect the client's automatic recovery
 * reconnects and re-declares the exchanges. A circuit breaker turns a sustained outage into
 * [PublishResult.CircuitOpen], which the relay reschedules WITHOUT counting an attempt, so a long broker outage does
 * not push valid events to DEAD.
 *
 * Topology: this publisher declares only the exchanges (idempotent, same arguments as the consumer and the operator
 * docs). Queues and bindings belong to the consumer.
 *
 * @param amqpUri e.g. `amqp://user:pass@rabbitmq:5672/carevoice` (5672 is the standard AMQP port, `carevoice` the
 *   virtual host).
 * @param confirmTimeout how long to wait for the broker's ack; also used as the TCP connect and handshake timeout.
 */
class AmqpEventPublisher(
    amqpUri: String,
    meterRegistry: MeterRegistry,
    private val confirmTimeout: Duration = 5.seconds,
    circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(),
    maxIdleChannels: Int = DEFAULT_MAX_IDLE_CHANNELS,
    private val clock: Clock = Clock.systemUTC(),
) : EventPublisher {
    private val factory: ConnectionFactory =
        ConnectionFactory().apply {
            setUri(amqpUri)
            // Reconnects after a broker restart or network blip and re-declares exchanges and channels (incl. confirm
            // mode and return listeners) without any code here.
            setAutomaticRecoveryEnabled(true)
            setTopologyRecoveryEnabled(true)
            setNetworkRecoveryInterval(NETWORK_RECOVERY_INTERVAL_MS)
            setConnectionTimeout(confirmTimeout.inWholeMilliseconds.toInt())
            setHandshakeTimeout(confirmTimeout.inWholeMilliseconds.toInt())
            setRequestedHeartbeat(HEARTBEAT_SECONDS)
            // Bounds every synchronous channel RPC (declare, confirm.select, close) instead of the 10-minute default,
            // so a broker that stops answering cannot pin a publishing coroutine.
            setChannelRpcTimeout(confirmTimeout.inWholeMilliseconds.toInt() * 2)
        }
    private val connectLock = Mutex()

    @Volatile
    private var connection: Connection? = null

    @Volatile
    private var closed = false
    private val channels = ChannelPool(maxIdleChannels)

    private val counters: Map<String, Counter> =
        RESULTS.associateWith {
            Counter
                .builder(PUBLISH_TOTAL_METRIC)
                .description("AMQP publishes by result")
                .tag("result", it)
                .register(meterRegistry)
        }

    /** Exposed for health checks and tests. */
    val circuitBreaker: CircuitBreaker = CircuitBreakers.create(
        CIRCUIT_BREAKER_NAME,
        circuitBreakerSettings,
        meterRegistry,
    )

    /** True while a connection to the broker is open; readiness reports `rabbitmq: DEGRADED` otherwise. */
    val isConnected: Boolean get() = connection?.isOpen == true

    /**
     * Opens the connection at startup instead of on the first publish, retrying with backoff until it succeeds.
     *
     * Without this, a replica that had nothing to publish yet never connected, and readiness reported
     * `rabbitmq: DEGRADED` although the broker was fine; with two replicas behind the load balancer, `/readyz`
     * flipped between UP and DEGRADED. Once a connection exists, the client's automatic recovery owns it, so the loop
     * ends: a broker outage after that is reported (DEGRADED) and recovered by the client, not by this loop.
     */
    fun connectInBackground(scope: CoroutineScope): Job = scope.launch {
        var pause = INITIAL_CONNECT_RETRY
        var failures = 0
        while (isActive && !closed && connection == null) {
            try {
                connection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                // One WARN per outage at startup, not one per retry.
                log.event(
                    if (failures == 1) Level.WARN else Level.DEBUG,
                    LogType.EXTERNAL_REQUEST,
                    "RabbitMQ not reachable at startup; retrying in the background",
                    "error" to e::class.simpleName,
                    "attempt" to failures,
                    "retryIn" to pause.toString(),
                )
                delay(pause)
                pause = minOf(pause * 2, MAX_CONNECT_RETRY)
            }
        }
    }

    /**
     * Publishes one event to RabbitMQ behind the circuit breaker (a switch that stops calls during a long outage)
     * and counts the result. Throws when the publisher was already closed.
     */
    override suspend fun publish(event: OutboxEvent): PublishResult {
        check(!closed) { "AmqpEventPublisher is closed" }
        val message = AmqpMessages.toMessage(event, clock)
        val result =
            when (val guarded = circuitBreaker.guard(isFailure = { it is PublishResult.Retryable }) { send(message) }) {
                is Guarded.Completed -> guarded.value
                Guarded.Rejected -> PublishResult.CircuitOpen
            }
        counters.getValue(resultTag(result)).increment()
        return result
    }

    /** Closes pooled channels and the connection. In-flight publishes should be finished first (relay stopped). */
    suspend fun close() {
        closed = true
        withContext(Dispatchers.IO) {
            connectLock.withLock {
                channels.closeAll()
                connection?.let { runCatching { it.close(CLOSE_TIMEOUT_MS) } }
                connection = null
            }
        }
    }

    /**
     * Borrows a channel, publishes the message and waits for the broker's confirm.
     * Only a channel whose publish was confirmed goes back to the pool; any other channel is closed.
     */
    private suspend fun send(message: AmqpMessage): PublishResult {
        // Step 1: borrow a channel. When the broker cannot be reached, the publish can be retried later.
        val pooled =
            try {
                borrow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.event(
                    Level.WARN,
                    LogType.EXTERNAL_REQUEST,
                    "RabbitMQ is unreachable",
                    "error" to e::class.simpleName,
                )
                return PublishResult.Retryable("broker unreachable: ${e::class.simpleName}")
            }
        // Step 2: publish and wait for the broker's confirm (its answer that it has taken the message).
        // The channel counts as healthy only when the message was published.
        var healthy = false
        try {
            val mark = TimeSource.Monotonic.markNow()
            val result = runInterruptible(Dispatchers.IO) { pooled.publishAndConfirm(message, confirmTimeout) }
            healthy = result == PublishResult.Published
            // Broker confirm (or nack/timeout) per message; the body is never logged.
            log.event(
                Level.DEBUG,
                LogType.EXTERNAL_REQUEST,
                "RabbitMQ publish confirmed",
                "exchange" to message.exchange,
                "routingKey" to message.routingKey,
                "messageId" to message.properties.messageId,
                "result" to resultTag(result),
                "durationMs" to mark.elapsedNow().inWholeMilliseconds,
            )
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PublishResult.Retryable("publish failed: ${e::class.simpleName}")
        } finally {
            // Step 3: a healthy channel goes back to the pool; any other one is closed.
            // Closing is a blocking RPC; it must run off the caller's thread and even when the caller was cancelled.
            withContext(NonCancellable + Dispatchers.IO) { channels.giveBack(pooled, keep = healthy && !closed) }
        }
    }

    /** Takes an open idle channel from the pool, or opens a new one on the shared connection. */
    private suspend fun borrow(): PooledChannel {
        channels.takeIdle()?.let { return it }
        val conn = connection()
        return runInterruptible(Dispatchers.IO) { PooledChannel.open(conn) }
    }

    /**
     * The shared connection, opened on first use. A [Mutex] makes concurrent workers wait for one connect attempt
     * instead of each opening its own connection.
     */
    private suspend fun connection(): Connection {
        // Step 1: fast path: an open connection is used as it is, without the lock.
        connection?.takeIf { it.isOpen }?.let { return it }
        // Step 2: take the lock and check again, because another worker may have connected while we waited.
        return connectLock.withLock {
            check(!closed) { "AmqpEventPublisher is closed" }
            connection?.takeIf { it.isOpen }?.let { return@withLock it }
            // A connection that is not open but exists is recovering automatically; a second one would leak.
            connection?.let { throw IllegalStateException("connection is recovering") }
            // Step 3: open a new connection and declare the exchanges on it. On failure the half-open
            // connection is aborted, so it does not leak.
            runInterruptible(Dispatchers.IO) {
                val opened = factory.newConnection(CONNECTION_NAME)
                try {
                    opened.createChannel().use { declareExchanges(it) }
                } catch (e: Exception) {
                    runCatching { opened.abort() }
                    throw e
                }
                log.event(Level.INFO, LogType.EXTERNAL_REQUEST, "Connected to RabbitMQ")
                opened
            }.also { connection = it }
        }
    }

    /** Declares the three durable exchanges; doing it again with the same arguments changes nothing. */
    private fun declareExchanges(channel: Channel) {
        channel.exchangeDeclare(EVENTS_EXCHANGE, BuiltinExchangeType.TOPIC, true)
        channel.exchangeDeclare(RETRY_EXCHANGE, BuiltinExchangeType.DIRECT, true)
        channel.exchangeDeclare(DEAD_LETTER_EXCHANGE, BuiltinExchangeType.DIRECT, true)
    }

    companion object {
        /** First pause between startup connect attempts; it doubles after each failure, up to [MAX_CONNECT_RETRY]. */
        private val INITIAL_CONNECT_RETRY = 1.seconds
        private val MAX_CONNECT_RETRY = 30.seconds

        const val EVENTS_EXCHANGE = "carevoice.events"
        const val RETRY_EXCHANGE = "carevoice.events.retry"
        const val DEAD_LETTER_EXCHANGE = "carevoice.events.dlx"
        const val CIRCUIT_BREAKER_NAME = "rabbitmq"
        const val CONNECTION_NAME = "documentation-service-publisher"

        /** `carevoice_amqp_publish_total{result}` */
        const val PUBLISH_TOTAL_METRIC = "carevoice.amqp.publish"

        private val RESULTS = listOf("published", "retry", "dead", "circuit_open")

        /** Most idle channels kept in the pool for reuse; a channel returned to a full pool is closed. */
        private const val DEFAULT_MAX_IDLE_CHANNELS = 16

        /** Pause between automatic reconnect attempts after the connection was lost, in milliseconds (5 s). */
        private const val NETWORK_RECOVERY_INTERVAL_MS = 5_000L

        /**
         * AMQP heartbeat timeout asked of the broker, in seconds. Both sides send small heartbeat frames, so a dead
         * TCP connection is noticed after about this long, not only when the next publish fails.
         * https://www.rabbitmq.com/docs/heartbeats
         */
        private const val HEARTBEAT_SECONDS = 30

        /** How long [close] waits for the connection close to finish, in milliseconds (5 s). */
        private const val CLOSE_TIMEOUT_MS = 5_000

        private val log = LoggerFactory.getLogger(AmqpEventPublisher::class.java)

        /** Metric tag for a publish result, one of [RESULTS]. */
        private fun resultTag(result: PublishResult): String = when (result) {
            PublishResult.Published -> "published"
            is PublishResult.Retryable -> "retry"
            is PublishResult.NonRetryable -> "dead"
            PublishResult.CircuitOpen -> "circuit_open"
        }
    }
}
