package com.carevoice.documentation.outbox

import com.carevoice.contracts.ContractJson
import com.carevoice.contracts.ObservationRecordedEvent
import com.carevoice.contracts.VitalPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.fail
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Shared test data for every publisher and relay test: a fresh event per call, never a mutated shared fixture. */
object OutboxTestFixtures {
    val FIXED_NOW: Instant = Instant.parse("2026-10-25T10:00:00Z")
    const val REQUEST_ID = "req-7f3a.42:abc"
    const val TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

    fun observationRecorded(
        eventId: UUID = UUID.randomUUID(),
        observationId: UUID = UUID.randomUUID(),
        occurredAt: Instant = FIXED_NOW,
    ): ObservationRecordedEvent = ObservationRecordedEvent(
        eventId = eventId.toString(),
        occurredAt = occurredAt.toString(),
        tenantId = "sunrise-care",
        wardId = "ward-a",
        residentId = "res-1",
        observationId = observationId.toString(),
        vital = VitalPayload.BloodPressure(systolic = 182, diastolic = 95),
        source = "RULES",
        status = "CONFIRMED",
    )

    /** An outbox row as the relay hands it to a publisher; the payload is encoded exactly like the writer's caller does. */
    fun outboxEvent(
        id: UUID = UUID.randomUUID(),
        requestId: String? = REQUEST_ID,
        traceparent: String? = TRACEPARENT,
        attempts: Int = 0,
        eventType: String = ObservationRecordedEvent.TYPE,
    ): OutboxEvent {
        val event = observationRecorded(eventId = id)
        return OutboxEvent(
            id = id,
            aggregateId = UUID.fromString(event.observationId),
            eventType = eventType,
            payload = ContractJson.encodeToString(ObservationRecordedEvent.serializer(), event),
            attempts = attempts,
            requestId = requestId,
            traceparent = traceparent,
        )
    }
}

/** A clock tests move forward explicitly, so backoff and lease arithmetic is exact. */
class MutableClock(private var now: Instant = OutboxTestFixtures.FIXED_NOW) : Clock() {
    @Synchronized
    override fun instant(): Instant = now

    @Synchronized
    fun advance(duration: Duration) {
        now = now.plus(duration.toJavaDuration())
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

/**
 * Bounded polling instead of sleeps: checks [condition] every few milliseconds and fails with a message naming
 * [what] was awaited when it does not hold within [timeout].
 */
fun awaitUntil(what: String, timeout: Duration = 10.seconds, condition: () -> Boolean) {
    val reached =
        runBlocking {
            withTimeoutOrNull(timeout) {
                while (!condition()) delay(POLL_INTERVAL)
                true
            }
        }
    if (reached != true) fail<Unit>("Timed out after $timeout waiting until $what")
}

/** Fails as soon as [condition] stops holding during [duration]; used to show that a limit is never exceeded. */
fun assertHoldsFor(what: String, duration: Duration, condition: () -> Boolean) {
    runBlocking {
        withTimeoutOrNull(duration) {
            while (true) {
                if (!condition()) fail<Unit>("Expected $what to hold for $duration, but it did not")
                delay(POLL_INTERVAL)
            }
        }
    }
}

private val POLL_INTERVAL = 10.milliseconds
