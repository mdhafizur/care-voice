package com.carevoice.documentation.application

import com.carevoice.contracts.ObservationRecordedEvent
import com.carevoice.contracts.VitalPayload
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import com.carevoice.domain.application.ProcessingResult
import com.carevoice.domain.application.TranscriptProcessor
import com.carevoice.domain.common.Either
import com.carevoice.domain.model.BlankInput
import com.carevoice.domain.model.BloodPressure
import com.carevoice.domain.model.BodyTemperature
import com.carevoice.domain.model.InconsistentValues
import com.carevoice.domain.model.OutOfRange
import com.carevoice.domain.model.Pulse
import com.carevoice.domain.model.ResidentId
import com.carevoice.domain.model.TooLong
import com.carevoice.domain.model.ValidationError
import com.carevoice.domain.model.VitalSign
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class SubmitTranscriptCommand(val residentId: String, val idempotencyKey: UUID, val transcript: String)

data class SubmitTranscriptOutcome(
    val result: TranscriptResult,
    /** True when this answers a retry with the same key and body: the stored response is returned (200, not 201). */
    val replayed: Boolean,
)

/**
 * Use case: a nurse's transcript in, stored observations out (`POST /v1/residents/{residentId}/transcripts`).
 *
 * Steps:
 * 1. Role check, then the resident must exist in the caller's ward (404 otherwise).
 * 2. Idempotency fast path: the key was already used by this user → replay the stored response (same body) or 409
 *    when the body differs.
 * 3. Extraction ([TranscriptProcessor], possibly calling the AI) runs OUTSIDE the database transaction: a slow model
 *    must never hold a pooled connection and row locks.
 * 4. Unit of Work: ONE transaction inserts the transcript (encrypted text + response body), its observations, one
 *    outbox row per observation (Transactional Outbox) and the audit row. All or nothing.
 * 5. Only after the commit: broadcast to SSE clients and invalidate the ward's query cache. Doing this before the
 *    commit could announce observations that are then rolled back.
 *
 * Idempotency race (two pods, same key, same moment): both pass step 2, both insert; the unique constraint
 * `(created_by, idempotency_key)` lets exactly one win. The loser's transaction is rolled back, and it then replays
 * the winner's stored response (or answers 409 when its body differs). No check-then-insert window exists.
 */
class SubmitTranscript(
    private val residents: ResidentRepository,
    private val transcripts: TranscriptRepository,
    private val observations: ObservationRepository,
    private val outbox: EventOutbox,
    private val audit: AuditLog,
    private val transactions: TransactionRunner,
    private val processor: TranscriptProcessor,
    private val broadcaster: ObservationBroadcaster,
    private val cacheBus: CacheInvalidationBus,
    private val correlation: CorrelationProvider,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    private val newTranscriptId: () -> UUID = UUID::randomUUID,
    private val newEventId: () -> UUID = UUID::randomUUID,
) {
    private val log = LoggerFactory.getLogger(SubmitTranscript::class.java)
    private val outcomeCounters: Map<String, Counter> =
        listOf(OUTCOME_PROCESSED, OUTCOME_REPLAYED, OUTCOME_REJECTED).associateWith {
            Counter
                .builder(TRANSCRIPTS_METRIC)
                .description("Submitted transcripts by outcome")
                .tag("outcome", it)
                .register(meterRegistry)
        }

    suspend fun execute(caller: Caller, command: SubmitTranscriptCommand): SubmitTranscriptOutcome {
        caller.requireAnyRole()
        val residentId =
            when (val parsed = ResidentId.of(command.residentId)) {
                is Either.Left -> throw ApplicationError.NotFound("Resident", command.residentId)
                is Either.Right -> parsed.value
            }
        val resident = residents.findById(caller.tenantId, residentId.value)
        if (resident == null || !caller.canSee(resident.tenantId, resident.wardId)) {
            throw ApplicationError.NotFound("Resident", command.residentId)
        }
        val requestHash = requestHash(command)

        transcripts.findByIdempotencyKey(caller.username, command.idempotencyKey)?.let { existing ->
            return replay(existing, requestHash)
        }

        val processed =
            when (val outcome = processor.process(residentId, command.transcript)) {
                is Either.Left -> {
                    outcomeCounters.getValue(OUTCOME_REJECTED).increment()
                    throw outcome.value.toUnprocessable()
                }

                is Either.Right -> outcome.value
            }
        logExtractionDetails(processed)

        val result = buildResult(caller, resident, processed)
        val record =
            TranscriptRecord(
                id = result.transcriptId,
                tenantId = caller.tenantId,
                wardId = resident.wardId,
                residentId = resident.id,
                createdBy = caller.username,
                idempotencyKey = command.idempotencyKey,
                requestHash = requestHash,
                result = result,
                createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
            )
        // Captured on the request coroutine, before any dispatcher switch: the trace context is thread-bound.
        val correlationIds = correlation.current()

        try {
            transactions.inTransaction {
                when (transcripts.insert(record, command.transcript)) {
                    TranscriptInsert.DuplicateIdempotencyKey -> throw IdempotencyRaceLost()
                    TranscriptInsert.Inserted -> Unit
                }
                observations.insertAll(result.observations)
                result.observations.forEach { outbox.append(it.toEvent(newEventId()), correlationIds) }
                audit.record(
                    AuditEntry(
                        tenantId = caller.tenantId,
                        actor = caller.username,
                        action = "TRANSCRIPT_SUBMITTED",
                        entityType = "transcript",
                        entityId = result.transcriptId.toString(),
                        occurredAt = record.createdAt,
                        details =
                        mapOf(
                            "residentId" to resident.id,
                            "observationCount" to result.observations.size.toString(),
                            "rejectedCount" to result.rejected.size.toString(),
                        ),
                    ),
                )
            }
        } catch (_: IdempotencyRaceLost) {
            val winner =
                transcripts.findByIdempotencyKey(caller.username, command.idempotencyKey)
                    ?: throw IllegalStateException(
                        "Idempotency key conflict, but the winning transcript is not visible",
                    )
            return replay(winner, requestHash)
        }

        outcomeCounters.getValue(OUTCOME_PROCESSED).increment()
        result.observations.forEach { recordObservationMetric(it) }
        log.event(
            Level.INFO,
            LogType.BUSINESS,
            "Transcript processed",
            "transcriptId" to result.transcriptId,
            "residentId" to resident.id,
            "observationCount" to result.observations.size,
            "rejectedCount" to result.rejected.size,
            "usedAiFallback" to result.usedAiFallback,
        )
        afterCommit(caller.tenantId, resident.wardId, result.observations)
        return SubmitTranscriptOutcome(result, replayed = false)
    }

    /**
     * DEBUG: why each value ended up as it did. Type, source, confidence and status only: never the measured value or
     * the matched text, which are health data and transcript content.
     */
    private fun logExtractionDetails(processed: ProcessingResult) {
        if (!log.isDebugEnabled) return
        processed.observations.forEach { observation ->
            log.event(
                Level.DEBUG,
                LogType.BUSINESS,
                "Value extracted",
                "observationId" to observation.id.toString(),
                "vitalType" to observation.vital::class.simpleName,
                "source" to observation.source.name,
                "confidence" to observation.confidence.value,
                "status" to observation.status.name,
            )
        }
        processed.rejected.forEach { rejected ->
            log.event(
                Level.DEBUG,
                LogType.BUSINESS,
                "Value rejected",
                "reason" to rejected.error::class.simpleName,
            )
        }
        if (processed.observations.isEmpty() && processed.rejected.isEmpty()) {
            log.event(
                Level.DEBUG,
                LogType.BUSINESS,
                "No value found",
                "usedAiFallback" to processed.usedAiFallback,
            )
        }
    }

    private fun replay(existing: TranscriptRecord, requestHash: String): SubmitTranscriptOutcome {
        if (existing.requestHash != requestHash) {
            throw ApplicationError.Conflict(
                slug = "idempotency-key-reused",
                detail = "This Idempotency-Key was already used with a different request body.",
            )
        }
        outcomeCounters.getValue(OUTCOME_REPLAYED).increment()
        log.event(
            Level.DEBUG,
            LogType.BUSINESS,
            "Idempotent replay: returning the stored result",
            "transcriptId" to existing.id,
        )
        return SubmitTranscriptOutcome(existing.result, replayed = true)
    }

    /**
     * Side effects that must happen only after the commit. They are best effort: the observations are stored and
     * the outbox will deliver the event regardless, so a Redis hiccup must not turn a successful request into a 500.
     */
    private suspend fun afterCommit(tenantId: String, wardId: String, stored: List<ObservationRecord>) {
        if (stored.isEmpty()) return
        try {
            broadcaster.publish(stored)
            cacheBus.publish(CacheInvalidation.ObservationQuery(tenantId, wardId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.event(Level.WARN, LogType.BUSINESS, "Post-commit notification failed", "wardId" to wardId, cause = e)
        }
    }

    private fun buildResult(caller: Caller, resident: Resident, processed: ProcessingResult): TranscriptResult {
        val transcriptId = newTranscriptId()
        return TranscriptResult(
            transcriptId = transcriptId,
            residentId = resident.id,
            observations =
            processed.observations.map {
                ObservationRecord(
                    // PostgreSQL stores microseconds; truncating here makes the 201 body equal every later read.
                    observation = it.copy(recordedAt = it.recordedAt.truncatedTo(ChronoUnit.MICROS)),
                    tenantId = caller.tenantId,
                    wardId = resident.wardId,
                    transcriptId = transcriptId,
                    confirmedBy = null,
                    confirmedAt = null,
                    version = 0,
                )
            },
            rejected = processed.rejected.map { RejectedValue(it.matchedText, it.error.message) },
            usedAiFallback = processed.usedAiFallback,
        )
    }

    private fun recordObservationMetric(record: ObservationRecord) {
        Counter
            .builder(OBSERVATIONS_METRIC)
            .description("Stored observations")
            .tag("type", record.observation.vital.type.name)
            .tag("source", record.observation.source.name)
            .tag("status", record.observation.status.name)
            .register(meterRegistry)
            .increment()
    }

    /** Signals "another request won the idempotency race" out of the transaction block, which rolls it back. */
    private class IdempotencyRaceLost : RuntimeException(null, null, false, false)

    companion object {
        const val TRANSCRIPTS_METRIC = "carevoice.transcripts"
        const val OBSERVATIONS_METRIC = "carevoice.observations"
        const val OUTCOME_PROCESSED = "processed"
        const val OUTCOME_REPLAYED = "replayed"
        const val OUTCOME_REJECTED = "rejected"

        /**
         * SHA-256 over the canonical request: the target resident and the exact transcript text. Same key with a
         * different resident or text is a different request → 409.
         */
        fun requestHash(command: SubmitTranscriptCommand): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(command.residentId.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(command.transcript.toByteArray(Charsets.UTF_8))
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}

/** Mapper: domain validation error → 422 with a field error. */
internal fun ValidationError.toUnprocessable(): ApplicationError.Unprocessable {
    val field =
        when (this) {
            is BlankInput -> fieldName
            is TooLong -> fieldName
            is OutOfRange -> fieldName
            is InconsistentValues -> "transcript"
        }
    return ApplicationError.Unprocessable("The request is semantically invalid.", listOf(FieldError(field, message)))
}

/** Mapper: stored observation → the published contract event (ids only, no personal data). */
internal fun ObservationRecord.toEvent(eventId: UUID): ObservationRecordedEvent = ObservationRecordedEvent(
    eventId = eventId.toString(),
    occurredAt = recordedAt.toString(),
    tenantId = tenantId,
    wardId = wardId,
    residentId = observation.residentId.value,
    observationId = id.toString(),
    vital = observation.vital.toPayload(),
    source = observation.source.name,
    status = observation.status.name,
)

internal fun VitalSign.toPayload(): VitalPayload = when (this) {
    is BloodPressure -> VitalPayload.BloodPressure(systolic, diastolic)
    is Pulse -> VitalPayload.Pulse(bpm)
    is BodyTemperature -> VitalPayload.BodyTemperature(celsius)
}
