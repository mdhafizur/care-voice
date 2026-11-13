package com.carevoice.documentation.outbox

import com.carevoice.documentation.resilience.Backoff
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * Delivers committed outbox rows to an [EventPublisher], at least once.
 *
 * ```
 *  claimer ──(Channel, capacity = channelCapacity)──▶ `concurrency` publisher workers ──▶ fenced UPDATE per row
 *     ▲ short claim tx: SELECT … FOR UPDATE SKIP LOCKED + lease                    publish happens OUTSIDE any tx
 * ```
 *
 * Why at-least-once plus an idempotent consumer instead of "exactly-once": exactly-once delivery over a network is
 * impossible in general. If the HTTP response is lost after alert-service committed, we cannot know whether it got
 * the event, so the only safe choice is to send again. Instead of pretending otherwise, every event carries its
 * `eventId` as `Idempotency-Key` and alert-service deduplicates on a unique index. Duplicates are harmless, a lost
 * event (a missed hypertensive-crisis alert) is not.
 *
 * Backpressure: the claimer only claims what the publishers can take. When alert-service slows down, the channel
 * fills, `send` suspends, and the claimer stops claiming; rows simply stay PENDING in the database (which is the real
 * queue) instead of piling up in memory.
 *
 * Structured concurrency: all coroutines are children of the job returned by [start]; cancelling the caller's scope
 * cancels them all. A [supervisorScope] isolates them, and every unit of work catches its own exceptions, so one bad
 * row or one failed database call never ends the loop. [CancellationException] is always rethrown so cancellation is
 * never swallowed.
 *
 * Files: this class runs the loops; [PublishResultRecorder] saves each publish result on its row, and [OutboxMetrics]
 * holds the gauges, counters and timers.
 */
class OutboxRelay(
    dataSource: DataSource,
    private val publisher: EventPublisher,
    private val settings: OutboxSettings,
    meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
    /** Source of the backoff jitter; injectable so tests can pin it. */
    private val random: Random = Random.Default,
) {
    private val repository = OutboxRepository(dataSource)
    private val stopping = MutableStateFlow(false)
    private val lifecycleLock = Any()
    private var running: Job? = null
    private val metrics = OutboxMetrics(meterRegistry)
    private val recorder = PublishResultRecorder(repository, settings, clock, random)

    /**
     * Starts the relay as a child of [scope] and returns its job. Call once; cancelling [scope] cancels the relay.
     */
    fun start(scope: CoroutineScope): Job = synchronized(lifecycleLock) {
        // Step 1: start only once, and never again after stop().
        check(running == null) { "OutboxRelay is already started" }
        check(!stopping.value) { "OutboxRelay cannot be restarted after stop()" }
        val job =
            scope.launch(CoroutineName("outbox-relay") + lastResortHandler) {
                supervisorScope {
                    // Step 2: start a stats loop, one claimer that takes due rows from the database, and the
                    // publisher workers. The channel (a small queue in memory) passes the claimed rows to them.
                    val stats = launch(CoroutineName("outbox-stats")) { statsLoop() }
                    val channel = Channel<ClaimedEvent>(settings.channelCapacity)
                    launch(CoroutineName("outbox-claimer")) { claimLoop(channel) }
                    val workers =
                        List(settings.concurrency) { index ->
                            launch(CoroutineName("outbox-publisher-$index")) { publishLoop(channel) }
                        }
                    // Step 3: wait for all workers to finish, then stop the stats loop.
                    // Workers end once the claimer has closed the channel and they have drained it (see stop()).
                    workers.joinAll()
                    stats.cancel()
                }
            }
        // Step 4: remember the job, so stop() can find it.
        running = job
        log.event(Level.INFO, LogType.BUSINESS, "Outbox relay started", "concurrency" to settings.concurrency)
        job
    }

    /**
     * Graceful stop: stops claiming, lets publishes that are already in flight finish and record their outcome, then
     * returns. Rows that were claimed but not yet started are released (made due immediately, attempts unchanged), so
     * another instance can take them at once instead of waiting for the lease.
     *
     * Releasing instead of draining keeps shutdown time bounded by ONE publish timeout (2 s), whatever is queued,
     * which matters inside a Kubernetes termination grace period.
     */
    suspend fun stop() {
        stopping.value = true
        val job = synchronized(lifecycleLock) { running } ?: return
        job.join()
        log.event(Level.INFO, LogType.BUSINESS, "Outbox relay stopped")
    }

    /**
     * Claims due rows until [stop] is called and hands them to the workers through [channel].
     * A failed claim waits with growing, jittered (randomized) pauses.
     * At the end the channel is closed, so the workers stop once it is empty.
     */
    private suspend fun claimLoop(channel: SendChannel<ClaimedEvent>) {
        var consecutiveFailures = 0
        try {
            while (!stopping.value) {
                val batch =
                    try {
                        claimBatch().also { consecutiveFailures = 0 }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Database unavailable or similar: back off (with jitter, so replicas do not reconnect in
                        // lockstep) and keep the loop alive. Nothing is lost; the rows stay PENDING in the database.
                        log.event(Level.ERROR, LogType.DB_OPERATION, "Outbox claim failed", cause = e)
                        pauseUnlessStopping(
                            Backoff.fullJitter(
                                consecutiveFailures++.coerceAtMost(MAX_CLAIM_BACKOFF_EXPONENT),
                                settings.pollInterval,
                                maxOf(settings.pollInterval, MAX_CLAIM_BACKOFF),
                                random,
                            ),
                        )
                        continue
                    }
                // Suspends while the channel is full: this is the backpressure point.
                for (claimed in batch) channel.send(claimed)
                // A full batch means there is probably more work right now; otherwise wait for the next poll.
                if (batch.size < settings.batchSize) pauseUnlessStopping(settings.pollInterval)
            }
        } finally {
            channel.close()
        }
    }

    /** Claims one batch of due rows and leases them for [OutboxSettings.lease], on [Dispatchers.IO]. */
    private suspend fun claimBatch(): List<ClaimedEvent> {
        val now = clock.instant()
        val mark = TimeSource.Monotonic.markNow()
        val batch =
            withContext(Dispatchers.IO) {
                repository.claim(now, now + settings.lease.toJavaDuration(), settings.batchSize)
            }
        if (batch.isNotEmpty()) {
            log.event(
                Level.DEBUG,
                LogType.DB_OPERATION,
                "Claimed outbox rows",
                "count" to batch.size,
                "durationMs" to mark.elapsedNow().inWholeMilliseconds,
            )
        }
        return batch
    }

    /** Worker loop: processes claimed rows until the channel is closed and empty; one row's error never stops it. */
    private suspend fun publishLoop(channel: ReceiveChannel<ClaimedEvent>) {
        for (claimed in channel) {
            try {
                process(claimed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Typically the outcome UPDATE failed (database down). The row keeps its lease and is retried when it
                // expires, so nothing is lost; the worker carries on with the next row.
                log.event(
                    Level.ERROR,
                    LogType.DB_OPERATION,
                    "Recording the outbox publish outcome failed",
                    "outboxEventId" to claimed.event.id,
                    cause = e,
                )
            }
        }
    }

    /**
     * Publishes one claimed row and records the outcome, with the event's requestId in the MDC (log context).
     * During shutdown the row is released instead; a row whose lease already ran out is skipped.
     */
    private suspend fun process(claimed: ClaimedEvent) {
        val event = claimed.event
        // The publish runs seconds after the original request, on another coroutine; restoring its requestId into
        // the MDC makes every log line of this publish (ours and the HTTP client's) searchable by that request.
        withContext(MDCContext(correlationOf(event))) {
            // Step 1: when the relay is stopping, give the row back for a later claim instead of publishing it.
            if (stopping.value) {
                withContext(Dispatchers.IO) { repository.reschedule(claimed, clock.instant()) }
                log.event(Level.DEBUG, LogType.BUSINESS, "Outbox row released on shutdown", "outboxEventId" to event.id)
                return@withContext
            }
            // Step 2: check the lease (the time this instance may own the row).
            if (!clock.instant().isBefore(claimed.leaseUntil)) {
                // Waited in the channel longer than the lease: another instance may own the row by now. Skip it; it
                // is due again and will be claimed normally (by us or by someone else).
                log.event(Level.WARN, LogType.BUSINESS, "Outbox lease expired before publishing, skipping")
                return@withContext
            }
            // Step 3: publish the event and measure how long it takes.
            log.event(
                Level.DEBUG,
                LogType.EXTERNAL_REQUEST,
                "Outbox event publishing",
                "outboxEventId" to event.id,
                "eventType" to event.eventType,
                "attempt" to event.attempts + 1,
                "leaseRemainingMs" to (claimed.leaseUntil.toEpochMilli() - clock.instant().toEpochMilli()),
            )
            val mark = TimeSource.Monotonic.markNow()
            val result = publishSafely(event)
            val elapsed = mark.elapsedNow()
            // Step 4: save the result on the row (published, retry later or dead), then update the metrics and log it.
            val outcome = withContext(Dispatchers.IO) { recorder.record(claimed, result) }
            metrics.recordPublish(outcome, elapsed)
            log.event(
                if (outcome == PublishOutcome.DEAD) Level.ERROR else Level.INFO,
                LogType.EXTERNAL_REQUEST,
                "Outbox event publish finished",
                "eventType" to event.eventType,
                "result" to outcome.tag,
                "attempt" to event.attempts + 1,
                "durationMs" to elapsed.inWholeMilliseconds,
                "reason" to result.reasonOrNull(),
            )
        }
    }

    /** A publisher bug must not kill the worker: an unexpected exception is treated like a transient failure. */
    private suspend fun publishSafely(event: OutboxEvent): PublishResult = try {
        publisher.publish(event)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.event(Level.ERROR, LogType.EXTERNAL_REQUEST, "Outbox publisher threw unexpectedly", cause = e)
        PublishResult.Retryable("publisher error: ${e::class.simpleName}")
    }

    /** Refreshes the pending-count and lag gauges, at most once per second, until cancelled. */
    private suspend fun statsLoop() {
        while (true) {
            try {
                val stats = withContext(Dispatchers.IO) { repository.pendingStats() }
                metrics.pending.set(stats.count)
                metrics.lagSeconds.set(stats.oldestCreatedAt?.let { secondsBetween(it, clock.instant()) } ?: 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.event(Level.WARN, LogType.DB_OPERATION, "Refreshing outbox gauges failed", cause = e)
            }
            delay(maxOf(settings.pollInterval, MIN_STATS_INTERVAL))
        }
    }

    /** Waits for [duration], but returns early as soon as [stop] is called, so shutdown never waits for a poll. */
    private suspend fun pauseUnlessStopping(duration: Duration) {
        withTimeoutOrNull(duration) { stopping.first { it } }
    }

    companion object {
        // Micrometer names are dot-separated; the Prometheus naming convention renders them as the contract names
        // in the KDoc (dots become underscores, counters get "_total", a gauge's base unit is appended). Keeping
        // Micrometer's convention lets the same meters export correctly to OTLP as well.

        /** `carevoice_outbox_pending` */
        const val PENDING_METRIC = "carevoice.outbox.pending"

        /** `carevoice_outbox_lag_seconds` */
        const val LAG_METRIC = "carevoice.outbox.lag"

        /** `carevoice_outbox_publish_total{result}` */
        const val PUBLISH_TOTAL_METRIC = "carevoice.outbox.publish"

        /** `carevoice_outbox_publish_seconds{result}` (already ends in the unit, so nothing is appended). */
        const val PUBLISH_SECONDS_METRIC = "carevoice.outbox.publish.seconds"

        /** MDC `job` value of every log line the relay writes while publishing (docs/CONTRACTS.md, section 14). */
        const val JOB_NAME = "outbox-relay"

        private val log = LoggerFactory.getLogger(OutboxRelay::class.java)

        /** Longest pause between two failed claims (for example while the database is down). */
        private val MAX_CLAIM_BACKOFF = 30.seconds

        /**
         * Highest attempt number passed to [Backoff.fullJitter] for a failed claim. 2^16 = 65,536, so any poll
         * interval of 1 ms or more already reaches [MAX_CLAIM_BACKOFF] here; a higher number would change nothing.
         */
        private const val MAX_CLAIM_BACKOFF_EXPONENT = 16

        /** The gauges are refreshed at most once per second, even when [OutboxSettings.pollInterval] is shorter. */
        private val MIN_STATS_INTERVAL = 1.seconds

        /**
         * Should never fire (every coroutine catches its own exceptions); if a bug slips through, it is logged
         * instead of reaching the thread's default handler with no context.
         */
        private val lastResortHandler =
            CoroutineExceptionHandler { _, e ->
                log.event(Level.ERROR, LogType.BUSINESS, "Uncaught exception in the outbox relay", cause = e)
            }

        /** MDC values for logging one event's publish: job name, outbox row id and the original requestId. */
        private fun correlationOf(event: OutboxEvent): Map<String, String> = buildMap {
            put("job", JOB_NAME)
            put("outboxEventId", event.id.toString())
            event.requestId?.let { put("requestId", it) }
        }

        /** Whole seconds from [from] to [to], never negative. */
        private fun secondsBetween(from: Instant, to: Instant): Long =
            java.time.Duration.between(from, to).seconds.coerceAtLeast(0)

        /** The failure reason for the log, or null for results that have none. */
        private fun PublishResult.reasonOrNull(): String? = when (this) {
            is PublishResult.Retryable -> reason
            is PublishResult.NonRetryable -> reason
            PublishResult.Published, PublishResult.CircuitOpen -> null
        }
    }
}
