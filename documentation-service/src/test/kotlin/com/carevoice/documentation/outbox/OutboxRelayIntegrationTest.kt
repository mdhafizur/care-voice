package com.carevoice.documentation.outbox

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The relay against real PostgreSQL (the production migrations) with a fake [EventPublisher]. Time is driven by a
 * [MutableClock], so "due", backoff and lease arithmetic are exact; waiting uses bounded polling, never sleeps.
 */
@DisplayName("OutboxRelay")
class OutboxRelayIntegrationTest {
    private val clock = MutableClock()
    private val meterRegistry = SimpleMeterRegistry()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val relays = CopyOnWriteArrayList<OutboxRelay>()
    private val now: Instant get() = clock.instant()

    @BeforeEach
    fun cleanTable() {
        OutboxDatabase.truncate()
    }

    @AfterEach
    fun stopRelays() {
        // Bounded, so a failed test whose fake publisher never returns cannot hang the suite; cancel cleans up the rest.
        runBlocking { withTimeoutOrNull(10.seconds) { relays.forEach { it.stop() } } }
        scope.cancel()
    }

    // The publisher comes last so tests can pass it as a trailing lambda: startRelay { PublishResult.Published }.
    private fun startRelay(settings: OutboxSettings = SETTINGS, publisher: EventPublisher): OutboxRelay =
        OutboxRelay(OutboxDatabase.dataSource, publisher, settings, meterRegistry, clock, Random(11)).also {
            relays += it
            it.start(scope)
        }

    private fun insertEvents(count: Int): List<OutboxEvent> =
        List(count) { OutboxTestFixtures.outboxEvent().also { OutboxDatabase.insert(it, now) } }

    private fun publishCount(result: String): Double =
        meterRegistry.find("carevoice.outbox.publish").tag("result", result).counter()?.count() ?: 0.0

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should mark the row published and record the metrics`() {
            val event = insertEvents(1).single()
            val published = CopyOnWriteArrayList<OutboxEvent>()
            startRelay {
                published += it
                PublishResult.Published
            }

            awaitUntil("the row is PUBLISHED") { OutboxDatabase.row(event.id).status == "PUBLISHED" }

            val row = OutboxDatabase.row(event.id)
            assertThat(row.publishedAt).isEqualTo(now)
            assertThat(row.attempts).isZero()
            assertThat(row.lastError).isNull()
            assertThat(published.map { it.id }).containsExactly(event.id)
            // JSONB normalises key order and whitespace: the published payload equals the event as JSON, not as text.
            assertThat(Json.parseToJsonElement(published.single().payload))
                .isEqualTo(Json.parseToJsonElement(event.payload))
            awaitUntil("the published counter is incremented") { publishCount("published") == 1.0 }
            val timer = meterRegistry.find("carevoice.outbox.publish.seconds").tag("result", "published").timer()
            assertThat(timer!!.count()).isEqualTo(1)
            assertThat(timer.takeSnapshot().histogramCounts()).isNotEmpty()
        }

        @Test
        fun `should pass requestId and traceparent through and restore them in the MDC`() {
            val event = insertEvents(1).single()
            val seen = CompletableDeferred<Pair<OutboxEvent, Map<String, String>>>()
            startRelay {
                seen.complete(it to (MDC.getCopyOfContextMap() ?: emptyMap()))
                PublishResult.Published
            }

            val (received, mdc) = runBlocking { seen.await() }

            assertThat(received.requestId).isEqualTo(OutboxTestFixtures.REQUEST_ID)
            assertThat(received.traceparent).isEqualTo(OutboxTestFixtures.TRACEPARENT)
            assertThat(mdc)
                .containsEntry("requestId", OutboxTestFixtures.REQUEST_ID)
                .containsEntry("outboxEventId", event.id.toString())
                .containsEntry("job", OutboxRelay.JOB_NAME)
        }

        @Test
        fun `should reschedule a retryable failure with backoff and count the attempt`() {
            val event = insertEvents(1).single()
            startRelay { PublishResult.Retryable("HTTP 500") }

            awaitUntil("the first attempt is recorded") { OutboxDatabase.row(event.id).attempts == 1 }

            val row = OutboxDatabase.row(event.id)
            assertThat(row.status).isEqualTo("PENDING")
            assertThat(row.lastError).isEqualTo("HTTP 500")
            // First failure: full jitter over [0, base].
            assertThat(row.nextAttemptAt).isBetween(now, now + SETTINGS.backoffBase)
            awaitUntil("the retry counter is incremented") { publishCount("retry") == 1.0 }
        }

        @Test
        fun `should retry again once the backoff has elapsed and then publish`() {
            val event = insertEvents(1).single()
            val calls = AtomicInteger()
            startRelay {
                if (calls.incrementAndGet() ==
                    1
                ) {
                    PublishResult.Retryable("timeout")
                } else {
                    PublishResult.Published
                }
            }

            awaitUntil("the first attempt is recorded") { OutboxDatabase.row(event.id).attempts == 1 }
            clock.advance(SETTINGS.backoffBase)

            awaitUntil("the row is PUBLISHED on the second attempt") {
                OutboxDatabase.row(event.id).status ==
                    "PUBLISHED"
            }
            assertThat(calls.get()).isEqualTo(2)
            assertThat(OutboxDatabase.row(event.id).lastError).isNull()
        }

        @Test
        fun `should honour Retry-After when it is longer than the backoff`() {
            val event = insertEvents(1).single()
            startRelay { PublishResult.Retryable("HTTP 503", retryAfter = 30.seconds) }

            awaitUntil("the attempt is recorded") { OutboxDatabase.row(event.id).attempts == 1 }

            assertThat(OutboxDatabase.row(event.id).nextAttemptAt).isEqualTo(now + 30.seconds)
        }

        @Test
        fun `should mark the row DEAD immediately on a non-retryable failure`() {
            val event = insertEvents(1).single()
            startRelay { PublishResult.NonRetryable("HTTP 422") }

            awaitUntil("the row is DEAD") { OutboxDatabase.row(event.id).status == "DEAD" }

            val row = OutboxDatabase.row(event.id)
            assertThat(row.attempts).isEqualTo(1)
            assertThat(row.lastError).isEqualTo("HTTP 422")
            awaitUntil("the dead counter is incremented") { publishCount("dead") == 1.0 }
        }

        @Test
        fun `should mark the row DEAD after the last allowed attempt`() {
            val event = insertEvents(1).single()
            OutboxDatabase.setAttempts(event.id, SETTINGS.maxAttempts - 1)
            startRelay { PublishResult.Retryable("HTTP 502") }

            awaitUntil("the row is DEAD") { OutboxDatabase.row(event.id).status == "DEAD" }

            val row = OutboxDatabase.row(event.id)
            assertThat(row.attempts).isEqualTo(SETTINGS.maxAttempts)
            assertThat(row.lastError).isEqualTo("gave up after ${SETTINGS.maxAttempts} attempts: HTTP 502")
        }

        @Test
        fun `should reschedule without counting an attempt while the circuit is open`() {
            val event = insertEvents(1).single()
            startRelay { PublishResult.CircuitOpen }

            awaitUntil("the circuit-open outcome is recorded") { publishCount("circuit_open") == 1.0 }

            val row = OutboxDatabase.row(event.id)
            assertThat(row.status).isEqualTo("PENDING")
            assertThat(row.attempts).isZero()
            assertThat(row.nextAttemptAt).isBetween(now + SETTINGS.lease / 2, now + SETTINGS.lease)
        }

        @Test
        fun `should treat a publisher that throws like a retryable failure and keep running`() {
            val first = insertEvents(1).single()
            val calls = AtomicInteger()
            startRelay { if (calls.incrementAndGet() == 1) error("bug in publisher") else PublishResult.Published }

            awaitUntil("the failed attempt is recorded") { OutboxDatabase.row(first.id).attempts == 1 }
            val second = insertEvents(1).single()

            awaitUntil("the next row is PUBLISHED") { OutboxDatabase.row(second.id).status == "PUBLISHED" }
            assertThat(OutboxDatabase.row(first.id).lastError).isEqualTo("publisher error: IllegalStateException")
        }

        @Test
        fun `should report pending rows and their age in the gauges`() {
            insertEvents(3)
            clock.advance(42.seconds)
            startRelay { PublishResult.CircuitOpen }

            awaitUntil("the pending gauge shows 3") {
                meterRegistry.find("carevoice.outbox.pending").gauge()?.value() ==
                    3.0
            }
            assertThat(meterRegistry.find("carevoice.outbox.lag").gauge()!!.value()).isEqualTo(42.0)
        }
    }

    @Nested
    @DisplayName("Concurrency and Backpressure")
    inner class Concurrency {
        @Test
        fun `should never publish the same row twice when two relays share the table`() {
            val events = insertEvents(200)
            val publications = ConcurrentHashMap<UUID, AtomicInteger>()
            val publisher =
                EventPublisher {
                    publications.computeIfAbsent(it.id) { AtomicInteger() }.incrementAndGet()
                    delay(1.milliseconds)
                    PublishResult.Published
                }
            startRelay(publisher = publisher)
            startRelay(publisher = publisher)

            awaitUntil("all 200 rows are PUBLISHED", 30.seconds) { OutboxDatabase.countByStatus("PUBLISHED") == 200 }

            assertThat(publications.keys).containsExactlyInAnyOrderElementsOf(events.map { it.id })
            assertThat(publications.values.map { it.get() }).allMatch { it == 1 }
        }

        @Test
        fun `should never run more publishes at once than the configured concurrency`() {
            insertEvents(30)
            val gate = CompletableDeferred<Unit>()
            val inFlight = AtomicInteger()
            val maxInFlight = AtomicInteger()
            startRelay(
                publisher = {
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
                    try {
                        gate.await()
                    } finally {
                        inFlight.decrementAndGet()
                    }
                    PublishResult.Published
                },
                settings = SETTINGS.copy(concurrency = 4),
            )

            awaitUntil("4 publishes are in flight") { inFlight.get() == 4 }
            assertHoldsFor("at most 4 publishes in flight", 300.milliseconds) { inFlight.get() <= 4 }
            gate.complete(Unit)

            awaitUntil("all rows are PUBLISHED") { OutboxDatabase.countByStatus("PUBLISHED") == 30 }
            assertThat(maxInFlight.get()).isEqualTo(4)
        }

        @Test
        fun `should stop claiming while the channel is full`() {
            insertEvents(20)
            val gate = CompletableDeferred<Unit>()
            val started = AtomicInteger()
            startRelay(
                publisher = {
                    started.incrementAndGet()
                    gate.await()
                    PublishResult.Published
                },
                settings = SETTINGS.copy(batchSize = 1, channelCapacity = 2, concurrency = 1),
            )

            awaitUntil("the first publish is in flight") { started.get() == 1 }
            // 1 in the publisher + 2 in the channel + 1 claimed row waiting in send() = at most 4 leased rows.
            assertHoldsFor("at most 4 rows leased", 300.milliseconds) { leasedRows() <= 4 }
            gate.complete(Unit)
            awaitUntil("all rows are PUBLISHED") { OutboxDatabase.countByStatus("PUBLISHED") == 20 }
        }

        private fun leasedRows(): Int = OutboxDatabase.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM outbox WHERE status = 'PENDING' AND next_attempt_at > ?",
            ).use {
                it.setObject(1, now.toDbTimestamp())
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    inner class Lifecycle {
        @Test
        fun `should let an in-flight publish finish and record it before stop returns`() {
            val inFlightEvent = insertEvents(1).single()
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val relay =
                startRelay(
                    publisher = {
                        started.complete(Unit)
                        gate.await()
                        PublishResult.Published
                    },
                    settings = SETTINGS.copy(concurrency = 1),
                )
            runBlocking { started.await() }
            val queued = insertEvents(3)

            val stopping = scope.async { relay.stop() }
            assertHoldsFor("stop() waits for the in-flight publish", 200.milliseconds) { !stopping.isCompleted }
            gate.complete(Unit)
            runBlocking { stopping.await() }

            assertThat(OutboxDatabase.row(inFlightEvent.id).status).isEqualTo("PUBLISHED")
            // Rows that were never started stay PENDING with their attempts untouched, ready for any instance.
            val queuedRows = queued.map { OutboxDatabase.row(it.id) }
            assertThat(queuedRows.map { it.status }).containsOnly("PENDING")
            assertThat(queuedRows.map { it.attempts }).containsOnly(0)
            assertThat(queuedRows.map { it.nextAttemptAt }).allMatch { !it.isAfter(now) }
        }

        @Test
        fun `should refuse to start twice`() {
            val relay = startRelay { PublishResult.Published }
            val outcome = runCatching { relay.start(scope) }
            assertThat(outcome.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        }
    }

    private companion object {
        val SETTINGS =
            OutboxSettings(
                batchSize = 50,
                channelCapacity = 100,
                concurrency = 8,
                pollInterval = 20.milliseconds,
                lease = 30.seconds,
                maxAttempts = 10,
                backoffBase = 500.milliseconds,
                backoffCap = 60.seconds,
            )

        operator fun Instant.plus(duration: Duration): Instant = plus(duration.toJavaDuration())
    }
}
