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
import io.micrometer.core.instrument.Gauge
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
 * - Transient failure (Mongo unavailable) → the message is published to the retry tier for its attempt (5 s, 30 s,
 *   2 min, 10 min, 30 min; [AmqpTopology.RETRY_DELAYS]) with the next attempt number in [HEADER_ATTEMPT], and the
 *   original is acked only after the broker confirmed the copy. Requeueing directly would spin the message at full
 *   speed while Mongo is down; growing delays ride out a failover or a short outage without hammering a recovering
 *   database. If that publish fails, the message is nacked and the main queue dead-letters it to the 5 s tier.
 * - When delivery [MAX_DELIVERIES] fails too, the message is published to the DLX and acked: a poison message must
 *   not circulate forever. `replay-dead-letters` moves it back once the cause is fixed ([DeadLetterReplayer]).
 * - Invalid JSON, unknown `schemaVersion` or invalid values can never succeed → straight to the DLX.
 * - MongoDB down (not a blip, [pauseWhileDown] confirms it): the consumer PAUSES instead of retrying. It cancels its
 *   subscription, puts every in-flight message back in the queue without counting an attempt (`nack` with requeue),
 *   checks the dependency every [healthPollInterval] and subscribes again once it is healthy. During a long outage the
 *   messages wait safely in the replicated queue, in order, instead of using up their retries and landing in the DLQ.
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
    /** When set, a dependency failure that this check confirms pauses consumption instead of retrying the message. */
    private val pauseWhileDown: DependencyHealthCheck? = null,
    private val healthPollInterval: Duration = 5.seconds,
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

    @Volatile private var paused = false

    private var scope: CoroutineScope? = null

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

    /** Counter of consumed events for one result (processed, duplicate, retried, ...); Micrometer reuses it. */
    private fun consumed(result: String): Counter = Counter
        .builder("carevoice.amqp.consume")
        .description("Consumed observation events by result")
        .tag("result", result)
        .register(registry)

    /** Connects (retrying in the background while the broker is unreachable) and starts the workers. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        Gauge
            .builder("carevoice.amqp.consumer.paused", this) { if (it.paused) 1.0 else 0.0 }
            .description("1 while consumption is paused because a dependency (MongoDB) is down")
            .register(registry)
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

    /** Ready only while the connection is open and the consumer is attached (or deliberately paused). */
    override suspend fun isHealthy(): Boolean =
        connected && (consumerTag != null || paused) && connection?.isOpen == true && consumeChannel?.isOpen == true

    /**
     * Opens the connection, declares the topology and subscribes.
     * It uses two channels: one to consume (limited by the prefetch count) and one to publish retries and
     * dead letters with publisher confirms (the broker reports that it has stored the message).
     */
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
                /** Marks the consumer as connected again after the client library recovered the connection. */
                override fun handleRecovery(recoverable: Recoverable) {
                    connected = true
                    log.event(Level.INFO, LogType.EXTERNAL_REQUEST, "RabbitMQ connection recovered")
                }

                /** Only logs that automatic recovery has started; the state changes in [handleRecovery]. */
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

        consumerTag = subscribe(channel)
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

    /** Consumes [AmqpTopology.QUEUE] with manual acks; returns the consumer tag (the id of this subscription). */
    private fun subscribe(channel: Channel): String = channel.basicConsume(
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

    /**
     * Puts the request id, trace ids and event id into the logging context (MDC), then processes and times it.
     * A request id with unexpected characters is left out.
     */
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

    /**
     * Parses the event, runs the use case and acks on success.
     * On failure it decides what happens to the message: bad input goes straight to the dead-letter exchange,
     * a dependency outage may pause the consumer, and anything else is retried later.
     * A message that arrives while paused is put back in the queue untouched.
     */
    private suspend fun process(delivery: Delivery, headers: Map<String, Any?>) {
        val tag = delivery.envelope.deliveryTag
        if (paused) {
            // Buffered before the pause: hand it back untouched, so it is processed once the dependency is back.
            requeue(tag)
            return
        }
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

                is AlertServiceException.DependencyUnavailable -> retryOrPause(delivery, e)

                is AlertServiceException.AlertNotFound,
                is AlertServiceException.AlreadyAcknowledged,
                is AlertServiceException.PreconditionRequired,
                is AlertServiceException.PreconditionFailed,
                is AlertServiceException.InvalidCursor,
                is AlertServiceException.RequestTimedOut,
                -> retryOrDeadLetter(delivery, e)
            }
        } catch (e: Exception) {
            retryOrDeadLetter(delivery, e)
        }
    }

    /**
     * A blip (the dependency answers again right away) is retried like any transient failure. A real outage pauses
     * consumption, and the message goes back to the queue without counting an attempt.
     */
    private suspend fun retryOrPause(delivery: Delivery, cause: AlertServiceException.DependencyUnavailable) {
        val health = pauseWhileDown
        if (health == null || scope == null || isUp(health)) {
            retryOrDeadLetter(delivery, cause)
            return
        }
        pause(health, cause.dependency)
        requeue(delivery.envelope.deliveryTag)
    }

    /**
     * Stops receiving new messages by cancelling the subscription, then starts a background loop that subscribes
     * again once [health] is up. Does nothing when already paused or stopping.
     */
    private suspend fun pause(health: DependencyHealthCheck, dependency: String) {
        val tagToCancel: String?
        synchronized(consumeLock) {
            if (paused || stopping) return
            paused = true
            tagToCancel = consumerTag
            consumerTag = null
        }
        withContext(Dispatchers.IO) {
            runCatching { tagToCancel?.let { tag -> synchronized(consumeLock) { consumeChannel?.basicCancel(tag) } } }
                .onFailure {
                    log.event(
                        Level.WARN,
                        LogType.EXTERNAL_REQUEST,
                        "Could not cancel the consumer for the pause",
                        cause = it,
                    )
                }
        }
        log.event(
            Level.WARN,
            LogType.EXTERNAL_REQUEST,
            "Consumption paused: dependency unavailable",
            "dependency" to dependency,
            "checkEveryMs" to healthPollInterval.inWholeMilliseconds,
        )
        scope?.launch(Dispatchers.IO) { resumeWhenHealthy(health) }
    }

    /** Checks [health] every [healthPollInterval] and subscribes again once it is up, until that works. */
    private suspend fun resumeWhenHealthy(health: DependencyHealthCheck) {
        while (!stopping) {
            delay(healthPollInterval)
            if (!isUp(health)) continue
            try {
                synchronized(consumeLock) {
                    val channel = consumeChannel?.takeIf { it.isOpen } ?: error("consume channel is not open")
                    consumerTag = subscribe(channel)
                    paused = false
                }
                log.event(Level.INFO, LogType.EXTERNAL_REQUEST, "Consumption resumed", "dependency" to health.name)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.event(
                    Level.WARN,
                    LogType.EXTERNAL_REQUEST,
                    "Could not resume consuming; retrying",
                    "error" to e.javaClass.simpleName,
                )
            }
        }
    }

    /** Asks the health check; an exception counts as not healthy. */
    private suspend fun isUp(health: DependencyHealthCheck): Boolean = try {
        health.isHealthy()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** `nack` with requeue: back to the queue for this or another pod, without a retry attempt or dead-lettering. */
    private suspend fun requeue(tag: Long) {
        withContext(Dispatchers.IO) { synchronized(consumeLock) { consumeChannel?.basicNack(tag, false, true) } }
        consumed("requeued").increment()
        log.event(
            Level.DEBUG,
            null,
            "Delivery requeued (consumer paused)",
            "deliveryTag" to tag,
            "decision" to "requeue",
        )
    }

    /**
     * Sends a failed message to the retry queue for its attempt, or to the dead-letter exchange after the last
     * allowed delivery. The original is acked only after the broker confirmed the copy.
     * If publishing the retry fails, the message is nacked so the main queue moves it to the 5 s retry queue.
     */
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
            return
        }
        val tier = AmqpTopology.retryTier(attempt)
        val delay = AmqpTopology.RETRY_DELAYS[tier]
        log.event(
            Level.WARN,
            null,
            "Event failed; retrying later",
            "attempt" to attempt,
            "maxDeliveries" to MAX_DELIVERIES,
            "retryInMs" to delay.inWholeMilliseconds,
            "error" to cause.javaClass.simpleName,
        )
        val decision =
            try {
                val headers = withoutDeathHistory(delivery.properties.headers.orEmpty()).toMutableMap()
                headers[HEADER_ATTEMPT] = attempt + 1
                publishConfirmed(delivery, AmqpTopology.RETRY_EXCHANGE, AmqpTopology.retryRoutingKey(tier), headers)
                ack(delivery.envelope.deliveryTag)
                "retry"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The copy may or may not have reached the broker; a duplicate is harmless (idempotent on eventId).
                // nack without requeue: the main queue dead-letters it to the first (5 s) tier.
                log.event(
                    Level.WARN,
                    LogType.EXTERNAL_REQUEST,
                    "Could not publish the retry; falling back to the 5 s retry queue",
                    "error" to e.javaClass.simpleName,
                )
                withContext(Dispatchers.IO) {
                    synchronized(consumeLock) { consumeChannel?.basicNack(delivery.envelope.deliveryTag, false, false) }
                }
                "retry-fallback"
            }
        log.event(
            Level.DEBUG,
            null,
            "Delivery settled for retry",
            "deliveryTag" to delivery.envelope.deliveryTag,
            "decision" to decision,
            "attempt" to attempt,
            "retryQueue" to AmqpTopology.retryQueue(tier),
        )
        consumed("retried").increment()
    }

    /**
     * Publishes the message (body and properties unchanged, plus the reason) to the DLX and waits for the broker's
     * confirm BEFORE acking the original, so a poison message is never lost between the two steps.
     */
    private suspend fun deadLetter(delivery: Delivery, reason: String) {
        val headers = delivery.properties.headers.orEmpty().toMutableMap()
        headers[HEADER_DEAD_LETTER_REASON] = reason
        headers[HEADER_ORIGINAL_ROUTING_KEY] = delivery.envelope.routingKey
        publishConfirmed(delivery, AmqpTopology.DEAD_LETTER_EXCHANGE, AmqpTopology.QUEUE_ROUTING_KEY, headers)
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

    /** Publishes the body with [headers] (other properties unchanged) and waits for the broker's confirm. */
    private suspend fun publishConfirmed(
        delivery: Delivery,
        exchange: String,
        routingKey: String,
        headers: Map<String, Any?>,
    ) {
        withContext(Dispatchers.IO) {
            val properties = delivery.properties.builder().headers(headers).build()
            synchronized(publishLock) {
                val channel = publishChannel ?: error("RabbitMQ publish channel is not open")
                channel.basicPublish(exchange, routingKey, false, properties, delivery.body)
                channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS)
            }
        }
    }

    /** Acks on the consume channel. The lock is needed because a channel must not be used by two threads at once. */
    private suspend fun ack(tag: Long) {
        withContext(Dispatchers.IO) { synchronized(consumeLock) { consumeChannel?.basicAck(tag, false) } }
    }

    /** Closes both channels and the connection, ignoring errors, and clears the state for a clean reconnect. */
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

        /** One delivery plus one per retry tier: the event is dead-lettered when delivery 6 fails (after ~43 min). */
        val MAX_DELIVERIES: Int = AmqpTopology.RETRY_DELAYS.size + 1
        const val HEADER_SCHEMA_VERSION = "schemaVersion"
        const val HEADER_REQUEST_ID = "requestId"
        const val HEADER_TRACEPARENT = "traceparent"
        const val HEADER_DEAD_LETTER_REASON = "x-carevoice-dead-letter-reason"
        const val HEADER_ORIGINAL_ROUTING_KEY = "x-carevoice-original-routing-key"

        /** Number of the NEXT delivery, set by this consumer when it schedules a retry. */
        const val HEADER_ATTEMPT = "x-carevoice-attempt"
        private const val CONFIRM_TIMEOUT_MS = 5_000L
        private const val CLOSE_TIMEOUT_MS = 5_000
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,64}")
        private val TRACEPARENT_PATTERN = Regex("^[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$")

        /**
         * Connection settings: automatic reconnect and topology recovery (queues and consumers are declared again)
         * every 5 s, a 30 s heartbeat (keep-alive signal) and a 5 s connect timeout.
         */
        fun defaultConnectionFactory(config: RabbitMqConfig): ConnectionFactory = ConnectionFactory().apply {
            setUri(config.url)
            setAutomaticRecoveryEnabled(true)
            setTopologyRecoveryEnabled(true)
            setNetworkRecoveryInterval(5_000L)
            setRequestedHeartbeat(30)
            setConnectionTimeout(5_000)
        }

        /**
         * Delivery number of the current delivery (1-based): [HEADER_ATTEMPT] (set by this consumer when it schedules a
         * retry, 1 when absent) plus how often THIS queue rejected the message since then, read from the `x-death`
         * header the broker maintains when dead-lettering (`count` per queue + reason). Rejections happen only on the
         * fallback path; a scheduled retry starts a fresh `x-death` history, so nothing is counted twice.
         */
        fun deliveryAttempt(headers: Map<String, Any?>): Int {
            val explicit = when (val value = headers[HEADER_ATTEMPT]) {
                is Number -> value.toInt()
                null -> null
                else -> value.toString().toIntOrNull()
            }?.coerceAtLeast(1) ?: 1
            val deaths = headers["x-death"] as? List<*> ?: return explicit
            val rejected =
                deaths
                    .filterIsInstance<Map<*, *>>()
                    .filter { it["queue"]?.toString() == AmqpTopology.QUEUE && it["reason"]?.toString() == "rejected" }
                    .sumOf { (it["count"] as? Number)?.toLong() ?: 0L }
            return (explicit + rejected).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /**
         * [headers] without the broker's dead-lettering history (`x-death`, `x-first-death-*`, `x-last-death-*`), so a
         * re-published copy starts counting afresh and [deliveryAttempt] never counts a rejection twice.
         */
        fun withoutDeathHistory(headers: Map<String, Any?>): Map<String, Any?> = headers.filterKeys { key ->
            key != "x-death" && !key.startsWith("x-first-death-") && !key.startsWith("x-last-death-")
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
