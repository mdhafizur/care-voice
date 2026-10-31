package com.carevoice.alert.infrastructure.amqp

import com.carevoice.alert.application.AlertServiceException
import com.carevoice.alert.application.DependencyHealthCheck
import com.carevoice.alert.application.EventParser
import com.carevoice.alert.application.ProcessObservationEvent
import com.carevoice.alert.config.RabbitMqConfig
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.ConsumerShutdownSignalCallback
import com.rabbitmq.client.DeliverCallback
import com.rabbitmq.client.Delivery
import com.rabbitmq.client.Recoverable
import com.rabbitmq.client.RecoveryListener
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.time.Duration as JavaDuration
import kotlinx.coroutines.channels.Channel as CoroutineChannel

/**
 * Adapter (driving side): consumes `ObservationRecordedEvent`s from RabbitMQ and hands them to the same
 * [ProcessObservationEvent] use case the HTTP endpoint uses (docs/CONTRACTS.md, section 8a).
 *
 * Delivery semantics (at-least-once, idempotent on `eventId`):
 * - Manual acks, and the ack is sent only AFTER the Mongo writes succeeded: if the pod dies mid-processing the broker
 *   redelivers the message to another consumer instead of losing it.
 * - Duplicate `eventId` → ack (the use case already returns the stored result).
 * - Transient failure (Mongo unavailable) → `basicNack(requeue = false)`: the queue dead-letters it to the retry
 *   queue, which returns it after 5 s. Requeueing directly would spin the message at full speed while Mongo is down.
 * - From the [MAX_DELIVERIES]th delivery on (counted from the `x-death` header) a failing message is published to the
 *   DLX and acked: a poison message must not circulate forever.
 * - Invalid JSON, unknown `schemaVersion` or invalid values can never succeed → straight to the DLX.
 *
 * Backpressure: `basicQos(prefetch)` caps how many unacked messages the broker pushes to this consumer, so a slow
 * pod (or a slow Mongo) makes messages wait in the replicated queue, where other pods can take them, instead of
 * piling up in this JVM's memory.
 *
 * Threading: the client library calls the delivery callback on its own thread pool. The callback only puts the
 * delivery into a bounded coroutine channel (blocking that thread when full, which is backpressure again); a fixed
 * number of worker coroutines process them. Acks are sent on the channel that delivered the message (delivery tags are
 * per channel) and serialised with a lock, because a Channel must not be used by several threads at once.
 *
 * Resilience: automatic connection and topology recovery; when the broker is unreachable at startup, connecting is
 * retried in the background and readiness stays DOWN until the consumer is attached.
 */
class ObservationEventConsumer(
    private val config: RabbitMqConfig,
    private val processObservationEvent: ProcessObservationEvent,
    private val registry: MeterRegistry,
    private val workers: Int = DEFAULT_WORKERS,
    private val reconnectDelay: Duration = 5.seconds,
    private val connectionFactory: ConnectionFactory = defaultConnectionFactory(config),
) : DependencyHealthCheck {
    private val log = LoggerFactory.getLogger(ObservationEventConsumer::class.java)

    override val name: String = "rabbitmq"

    @Volatile private var connection: Connection? = null

    @Volatile private var consumeChannel: Channel? = null

    @Volatile private var publishChannel: Channel? = null

    @Volatile private var consumerTag: String? = null

    @Volatile private var connected = false

    @Volatile private var stopping = false

    private val consumeLock = Any()
    private val publishLock = Any()
    private val deliveries = CoroutineChannel<Delivery>(capacity = config.prefetch)
    private var supervisor: Job? = null
    private val workerJobs = mutableListOf<Job>()

    private val processingTimer: Timer =
        Timer
            .builder("carevoice.amqp.processing")
            .description("Time to process one consumed observation event, from delivery to ack")
            .publishPercentileHistogram()
            .minimumExpectedValue(JavaDuration.ofMillis(1))
            .maximumExpectedValue(JavaDuration.ofSeconds(10))
            .register(registry)

    private fun consumed(result: String): Counter = Counter
        .builder("carevoice.amqp.consume")
        .description("Consumed observation events by result")
        .tag("result", result)
        .register(registry)

    /** Connects (retrying in the background while the broker is unreachable) and starts the workers. */
    fun start(scope: CoroutineScope) {
        repeat(workers) { index ->
            workerJobs +=
                scope.launch(Dispatchers.Default) {
                    for (delivery in deliveries) {
                        try {
                            handle(delivery)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // E.g. the ack failed because the channel was replaced by connection recovery: the broker
                            // redelivers the unacked message, so the worker logs and carries on.
                            log.event(
                                Level.ERROR,
                                LogType.EXTERNAL_REQUEST,
                                "Could not settle delivery",
                                "deliveryTag" to delivery.envelope.deliveryTag,
                                cause = e,
                            )
                        }
                    }
                    log.event(Level.DEBUG, null, "AMQP worker stopped", "worker" to index)
                }
        }
        supervisor =
            scope.launch(Dispatchers.IO) {
                while (isActive && !stopping && consumerTag == null) {
                    try {
                        connect()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.event(
                            Level.WARN,
                            LogType.EXTERNAL_REQUEST,
                            "RabbitMQ unavailable; retrying",
                            "error" to e.javaClass.simpleName,
                            "retryIn" to reconnectDelay.toString(),
                        )
                        closeQuietly()
                        delay(reconnectDelay)
                    }
                }
            }
    }

    /**
     * Graceful stop: cancel the consumer (the broker stops sending), let the workers finish the messages already
     * received, then close the channels and the connection. Messages that were not acked are redelivered by the broker
     * to another pod.
     */
    suspend fun stop(timeout: Duration = 10.seconds) {
        stopping = true
        supervisor?.cancel()
        withContext(Dispatchers.IO) {
            consumerTag?.let { tag ->
                runCatching { synchronized(consumeLock) { consumeChannel?.basicCancel(tag) } }
                    .onFailure {
                        log.event(
                            Level.WARN,
                            LogType.EXTERNAL_REQUEST,
                            "Could not cancel the RabbitMQ consumer",
                            cause = it,
                        )
                    }
            }
        }
        deliveries.close()
        val drained = withTimeoutOrNull(timeout) { workerJobs.joinAll() } != null
        if (!drained) {
            log.event(
                Level.WARN,
                null,
                "AMQP workers did not finish in time; unacked messages will be redelivered",
                "timeout" to timeout.toString(),
            )
            workerJobs.forEach { it.cancel() }
        }
        withContext(Dispatchers.IO) { closeQuietly() }
        log.info("RabbitMQ consumer stopped")
    }

    /** Ready only while the connection is open and the consumer is attached. */
    override suspend fun isHealthy(): Boolean =
        connected && consumerTag != null && connection?.isOpen == true && consumeChannel?.isOpen == true

    private fun connect() {
        val conn = connectionFactory.newConnection("alert-service-consumer")
        connection = conn
        conn.addShutdownListener { cause ->
            connected = false
            if (!cause.isInitiatedByApplication) {
                log.event(Level.WARN, LogType.EXTERNAL_REQUEST, "RabbitMQ connection lost", "reason" to cause.message)
            }
        }
        (conn as? Recoverable)?.addRecoveryListener(
            object : RecoveryListener {
                override fun handleRecovery(recoverable: Recoverable) {
                    connected = true
                    log.event(Level.INFO, LogType.EXTERNAL_REQUEST, "RabbitMQ connection recovered")
                }

                override fun handleRecoveryStarted(recoverable: Recoverable) {
                    log.event(Level.INFO, LogType.EXTERNAL_REQUEST, "RabbitMQ connection recovery started")
                }
            },
        )

        val channel = conn.createChannel()
        AmqpTopology.declare(channel)
        channel.basicQos(config.prefetch)
        consumeChannel = channel

        val publisher = conn.createChannel()
        publisher.confirmSelect()
        publishChannel = publisher

        consumerTag =
            channel.basicConsume(
                AmqpTopology.QUEUE,
                false,
                DeliverCallback { _, delivery -> enqueue(delivery) },
                CancelCallback { tag ->
                    log.event(
                        Level.WARN,
                        LogType.EXTERNAL_REQUEST,
                        "RabbitMQ consumer was cancelled by the broker",
                        "consumerTag" to tag,
                    )
                },
                ConsumerShutdownSignalCallback { tag, signal ->
                    if (!signal.isInitiatedByApplication) {
                        log.event(
                            Level.WARN,
                            LogType.EXTERNAL_REQUEST,
                            "RabbitMQ consumer shut down",
                            "consumerTag" to tag,
                            "reason" to signal.message,
                        )
                    }
                },
            )
        connected = true
        log.event(
            Level.INFO,
            LogType.EXTERNAL_REQUEST,
            "RabbitMQ consumer attached",
            "queue" to AmqpTopology.QUEUE,
            "prefetch" to config.prefetch,
            "workers" to workers,
        )
    }

    /** Runs on the client library's thread: blocks it while the buffer is full (backpressure, never drops). */
    private fun enqueue(delivery: Delivery) {
        val result = deliveries.trySendBlocking(delivery)
        if (result.isFailure) {
            // Only while stopping: the message stays unacked and is redelivered after the channel closes.
            log.event(
                Level.DEBUG,
                null,
                "Delivery not accepted (consumer stopping)",
                "deliveryTag" to delivery.envelope.deliveryTag,
            )
        }
    }

    private suspend fun handle(delivery: Delivery) {
        val headers = delivery.properties.headers.orEmpty()
        val requestId = headers[HEADER_REQUEST_ID]?.toString()
        val traceparent = headers[HEADER_TRACEPARENT]?.toString()
        val mdc =
            buildMap {
                put("type", "business")
                requestId?.takeIf { REQUEST_ID_PATTERN.matches(it) }?.let { put("requestId", it) }
                traceContext(traceparent)?.let { (traceId, spanId) ->
                    put("traceId", traceId)
                    put("spanId", spanId)
                }
                delivery.properties.messageId?.let { put("eventId", it) }
            }
        withContext(MDCContext(mdc)) {
            val sample = Timer.start(registry)
            try {
                process(delivery, headers)
            } finally {
                sample.stop(processingTimer)
            }
        }
    }

    private suspend fun process(delivery: Delivery, headers: Map<String, Any?>) {
        val tag = delivery.envelope.deliveryTag
        log.event(
            Level.DEBUG,
            null,
            "Delivery received",
            "deliveryTag" to tag,
            "redelivered" to delivery.envelope.isRedeliver,
            "attempt" to deliveryAttempt(headers),
            "schemaVersion" to headerSchemaVersion(headers),
            "bytes" to delivery.body.size,
        )
        try {
            headerSchemaVersion(headers)?.let { EventParser.requireSupported(it) }
            val event = EventParser.parse(String(delivery.body, Charsets.UTF_8))
            val result = processObservationEvent.execute(event)
            ack(tag)
            log.event(Level.DEBUG, null, "Delivery acked", "deliveryTag" to tag, "decision" to "ack")
            consumed(if (result.duplicate) "duplicate" else "processed").increment()
            // eventId, requestId and the trace ids are in the MDC (type=business) for every line of this event.
            log.event(
                Level.INFO,
                null,
                "Event processed",
                "alertsCreated" to result.alertsCreated,
                "duplicate" to result.duplicate,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AlertServiceException) {
            when (e) {
                is AlertServiceException.MalformedEvent,
                is AlertServiceException.UnsupportedSchemaVersion,
                is AlertServiceException.InvalidEvent,
                -> deadLetter(delivery, reason = e.javaClass.simpleName)

                is AlertServiceException.DependencyUnavailable -> retryOrDeadLetter(delivery, e)

                is AlertServiceException.AlertNotFound,
                is AlertServiceException.AlreadyAcknowledged,
                is AlertServiceException.PreconditionRequired,
                is AlertServiceException.PreconditionFailed,
                is AlertServiceException.InvalidCursor,
                -> retryOrDeadLetter(delivery, e)
            }
        } catch (e: Exception) {
            retryOrDeadLetter(delivery, e)
        }
    }

    private suspend fun retryOrDeadLetter(delivery: Delivery, cause: Exception) {
        val attempt = deliveryAttempt(delivery.properties.headers.orEmpty())
        if (attempt >= MAX_DELIVERIES) {
            log.event(
                Level.ERROR,
                null,
                "Event failed on its last delivery; dead-lettering it",
                "attempt" to attempt,
                "maxDeliveries" to MAX_DELIVERIES,
                cause = cause,
            )
            deadLetter(delivery, reason = "max-deliveries-exceeded: ${cause.javaClass.simpleName}")
        } else {
            log.event(
                Level.WARN,
                null,
                "Event failed; retrying in 5 s",
                "attempt" to attempt,
                "error" to cause.javaClass.simpleName,
            )
            withContext(Dispatchers.IO) {
                synchronized(consumeLock) { consumeChannel?.basicNack(delivery.envelope.deliveryTag, false, false) }
            }
            // nack without requeue: the dead-letter route of the main queue sends it to the 5 s retry queue.
            log.event(
                Level.DEBUG,
                null,
                "Delivery nacked for retry",
                "deliveryTag" to delivery.envelope.deliveryTag,
                "decision" to "retry",
                "attempt" to attempt,
                "maxDeliveries" to MAX_DELIVERIES,
            )
            consumed("retried").increment()
        }
    }

    /**
     * Publishes the message (body and properties unchanged, plus the reason) to the DLX and waits for the broker's
     * confirm BEFORE acking the original, so a poison message is never lost between the two steps.
     */
    private suspend fun deadLetter(delivery: Delivery, reason: String) {
        withContext(Dispatchers.IO) {
            val headers = delivery.properties.headers.orEmpty().toMutableMap()
            headers[HEADER_DEAD_LETTER_REASON] = reason
            headers[HEADER_ORIGINAL_ROUTING_KEY] = delivery.envelope.routingKey
            val properties = delivery.properties.builder().headers(headers).build()
            synchronized(publishLock) {
                val channel = publishChannel ?: error("RabbitMQ publish channel is not open")
                channel.basicPublish(
                    AmqpTopology.DEAD_LETTER_EXCHANGE,
                    AmqpTopology.QUEUE_ROUTING_KEY,
                    false,
                    properties,
                    delivery.body,
                )
                channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS)
            }
        }
        ack(delivery.envelope.deliveryTag)
        consumed("dead_lettered").increment()
        log.event(
            Level.WARN,
            null,
            "Event dead-lettered",
            "exchange" to AmqpTopology.DEAD_LETTER_EXCHANGE,
            "reason" to reason,
        )
    }

    private suspend fun ack(tag: Long) {
        withContext(Dispatchers.IO) { synchronized(consumeLock) { consumeChannel?.basicAck(tag, false) } }
    }

    private fun closeQuietly() {
        connected = false
        consumerTag = null
        listOf(publishChannel, consumeChannel).forEach { channel ->
            runCatching { if (channel?.isOpen == true) channel.close() }
        }
        runCatching { connection?.takeIf { it.isOpen }?.close(CLOSE_TIMEOUT_MS) }
        publishChannel = null
        consumeChannel = null
        connection = null
    }

    companion object {
        const val DEFAULT_WORKERS = 8
        const val MAX_DELIVERIES = 5
        const val HEADER_SCHEMA_VERSION = "schemaVersion"
        const val HEADER_REQUEST_ID = "requestId"
        const val HEADER_TRACEPARENT = "traceparent"
        const val HEADER_DEAD_LETTER_REASON = "x-carevoice-dead-letter-reason"
        const val HEADER_ORIGINAL_ROUTING_KEY = "x-carevoice-original-routing-key"
        private const val CONFIRM_TIMEOUT_MS = 5_000L
        private const val CLOSE_TIMEOUT_MS = 5_000
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,64}")
        private val TRACEPARENT_PATTERN = Regex("^[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$")

        fun defaultConnectionFactory(config: RabbitMqConfig): ConnectionFactory = ConnectionFactory().apply {
            setUri(config.url)
            setAutomaticRecoveryEnabled(true)
            setTopologyRecoveryEnabled(true)
            setNetworkRecoveryInterval(5_000L)
            setRequestedHeartbeat(30)
            setConnectionTimeout(5_000)
        }

        /**
         * Delivery number of the current delivery: 1 + how often THIS queue rejected the message, read from the
         * `x-death` header the broker maintains when dead-lettering (`count` per queue + reason).
         */
        fun deliveryAttempt(headers: Map<String, Any?>): Int {
            val deaths = headers["x-death"] as? List<*> ?: return 1
            val rejected =
                deaths
                    .filterIsInstance<Map<*, *>>()
                    .filter { it["queue"]?.toString() == AmqpTopology.QUEUE && it["reason"]?.toString() == "rejected" }
                    .sumOf { (it["count"] as? Number)?.toLong() ?: 0L }
            return (rejected + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /** The `schemaVersion` header (Int on the wire, but tolerate a numeric string); null when absent. */
        fun headerSchemaVersion(headers: Map<String, Any?>): Int? = when (val value = headers[HEADER_SCHEMA_VERSION]) {
            null -> null

            is Number -> value.toInt()

            else ->
                value.toString().toIntOrNull()
                    ?: throw AlertServiceException.MalformedEvent("schemaVersion header is not an integer")
        }

        /** (traceId, spanId) from a W3C `traceparent`, or null when absent or malformed. */
        fun traceContext(traceparent: String?): Pair<String, String>? {
            val match = traceparent?.let { TRACEPARENT_PATTERN.matchEntire(it) } ?: return null
            return match.groupValues[1] to match.groupValues[2]
        }
    }
}
