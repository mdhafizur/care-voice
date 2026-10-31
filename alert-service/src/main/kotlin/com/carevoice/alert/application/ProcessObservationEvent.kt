package com.carevoice.alert.application

import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.AlertRule
import com.carevoice.alert.domain.Observation
import com.carevoice.alert.domain.ObservationSource
import com.carevoice.alert.domain.ObservationStatus
import com.carevoice.alert.domain.Vital
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import com.carevoice.contracts.ObservationRecordedEvent
import com.carevoice.contracts.VitalPayload
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** Outcome of processing one event; [duplicate] is true when [eventId] had been processed before. */
data class ProcessingResult(val eventId: String, val alertsCreated: Int, val duplicate: Boolean)

/**
 * Use case: evaluate the alert rules for one [ObservationRecordedEvent] and store the alerts, exactly once per
 * `eventId`. Shared by both transports (HTTP `POST /v1/events/observations` and the RabbitMQ consumer), so delivery is
 * at-least-once and idempotent the same way on either path.
 *
 * Idempotency without check-then-insert: the `processed_events` record is INSERTED FIRST and the unique index on
 * `eventId` decides who processes the event. A "find, then insert if absent" sequence has a window in which two pods
 * (or a redelivery racing the original) both see "absent" and both create alerts; the unique index has no such window.
 * The loser receives the stored result.
 *
 * Crash safety: the record is written as incomplete, the alerts are inserted (unique `eventId + rule`, so a retry
 * cannot duplicate them), then the record is marked completed. A delivery that finds an INCOMPLETE record re-runs the
 * idempotent alert insert instead of trusting it, so a crash between the two writes never loses an alert.
 */
class ProcessObservationEvent(
    private val evaluator: AlertRule,
    private val alerts: AlertRepository,
    private val processedEvents: ProcessedEventRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val metrics: AlertMetrics = AlertMetrics.NONE,
) {
    private val log = LoggerFactory.getLogger(ProcessObservationEvent::class.java)

    suspend fun execute(event: ObservationRecordedEvent): ProcessingResult {
        val observation = validate(event)
        // Mongo stores dates with millisecond precision; truncating here keeps stored and returned values identical.
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val findings = evaluator.evaluate(observation)
        log.event(
            Level.DEBUG,
            LogType.BUSINESS,
            "Alert rules evaluated",
            "eventId" to event.eventId,
            "observationId" to observation.observationId,
            "vitalType" to observation.vital::class.simpleName,
            "observationStatus" to observation.status.name,
            "rulesFired" to findings.joinToString(",") { it.rule.name }.ifEmpty { "none" },
        )
        val newAlerts =
            findings.map { finding ->
                Alert.open(ids.next(), event.eventId, observation, finding, createdAt = now)
            }
        val record =
            ProcessedEvent(
                eventId = event.eventId,
                tenantId = observation.tenantId,
                wardId = observation.wardId,
                observationId = observation.observationId,
                alertsCreated = newAlerts.size,
                processedAt = now,
                completed = false,
            )

        if (processedEvents.tryInsert(record)) {
            storeAlerts(event.eventId, newAlerts)
            metrics.eventProcessed(duplicate = false)
            return ProcessingResult(event.eventId, newAlerts.size, duplicate = false)
        }

        val existing = processedEvents.find(event.eventId)
        log.event(
            Level.DEBUG,
            LogType.BUSINESS,
            "Event already processed",
            "eventId" to event.eventId,
            "completed" to existing?.completed,
        )
        if (existing != null && !existing.completed) {
            // An earlier attempt stopped (or is still running) between the two writes: finishing is idempotent.
            storeAlerts(event.eventId, newAlerts)
        }
        metrics.eventProcessed(duplicate = true)
        return ProcessingResult(event.eventId, existing?.alertsCreated ?: newAlerts.size, duplicate = true)
    }

    private suspend fun storeAlerts(eventId: String, newAlerts: List<Alert>) {
        val inserted = if (newAlerts.isEmpty()) emptyList() else alerts.insertAll(newAlerts)
        processedEvents.markCompleted(eventId)
        metrics.alertsCreated(inserted)
        // One line per new alert (a redelivery inserts nothing, so it logs nothing): ids and the rule only.
        for (alert in inserted) {
            log.event(
                Level.INFO,
                LogType.BUSINESS,
                "Alert raised",
                "alertId" to alert.id,
                "rule" to alert.rule.name,
                "severity" to alert.severity.name,
                "observationId" to alert.observationId,
                "eventId" to eventId,
            )
        }
    }

    /** Throws [AlertServiceException.UnsupportedSchemaVersion] or [AlertServiceException.InvalidEvent]. */
    private fun validate(event: ObservationRecordedEvent): Observation {
        if (event.schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            throw AlertServiceException.UnsupportedSchemaVersion(event.schemaVersion, SUPPORTED_SCHEMA_VERSIONS)
        }
        val violations = mutableListOf<FieldViolation>()
        fun id(field: String, value: String) {
            if (value.isBlank()) {
                violations += FieldViolation(field, "must not be blank")
            } else if (value.length > MAX_ID_LENGTH) {
                violations += FieldViolation(field, "must be at most $MAX_ID_LENGTH characters")
            }
        }
        id("eventId", event.eventId)
        id("tenantId", event.tenantId)
        id("wardId", event.wardId)
        id("residentId", event.residentId)
        id("observationId", event.observationId)

        val observedAt =
            try {
                Instant.parse(event.occurredAt).truncatedTo(ChronoUnit.MILLIS)
            } catch (_: DateTimeParseException) {
                violations += FieldViolation("occurredAt", "must be an ISO-8601 instant")
                null
            }
        val source = enumValueOrNull<ObservationSource>(event.source)
        if (source == null) violations += FieldViolation("source", "must be one of ${ObservationSource.entries}")
        val status = enumValueOrNull<ObservationStatus>(event.status)
        if (status == null) violations += FieldViolation("status", "must be one of ${ObservationStatus.entries}")
        val vital = toVital(event.vital, violations)

        if (violations.isNotEmpty()) throw AlertServiceException.InvalidEvent(violations)
        return Observation(
            observationId = event.observationId,
            tenantId = event.tenantId,
            wardId = event.wardId,
            residentId = event.residentId,
            vital = vital!!,
            source = source!!,
            status = status!!,
            observedAt = observedAt!!,
        )
    }

    private fun toVital(payload: VitalPayload, violations: MutableList<FieldViolation>): Vital? {
        val before = violations.size
        val vital =
            when (payload) {
                is VitalPayload.BloodPressure -> {
                    if (payload.systolic !in BP_RANGE) {
                        violations += FieldViolation("vital.systolic", "must be in $BP_RANGE")
                    }
                    if (payload.diastolic !in BP_RANGE) {
                        violations += FieldViolation("vital.diastolic", "must be in $BP_RANGE")
                    }
                    Vital.BloodPressure(payload.systolic, payload.diastolic)
                }

                is VitalPayload.Pulse -> {
                    if (payload.bpm !in PULSE_RANGE) {
                        violations += FieldViolation("vital.bpm", "must be in $PULSE_RANGE")
                    }
                    Vital.Pulse(payload.bpm)
                }

                is VitalPayload.BodyTemperature -> {
                    if (!payload.celsius.isFinite() || payload.celsius !in CELSIUS_RANGE) {
                        violations += FieldViolation("vital.celsius", "must be in $CELSIUS_RANGE")
                    }
                    Vital.BodyTemperature(payload.celsius)
                }
            }
        return if (violations.size == before) vital else null
    }

    companion object {
        val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(ObservationRecordedEvent.SCHEMA_VERSION)
        const val MAX_ID_LENGTH = 64

        /**
         * Physically possible values only. Clinical plausibility is documentation-service's job; this guards the alert
         * store against corrupt events (e.g. a negative pulse) without second-guessing the producer's validation.
         */
        val BP_RANGE = 1..400
        val PULSE_RANGE = 1..400
        val CELSIUS_RANGE = 10.0..50.0

        private inline fun <reified E : Enum<E>> enumValueOrNull(value: String): E? =
            enumValues<E>().firstOrNull { it.name == value }
    }
}
