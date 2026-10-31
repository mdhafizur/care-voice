package com.carevoice.documentation.infrastructure.serialization

import com.carevoice.documentation.application.ObservationRecord
import com.carevoice.documentation.application.RejectedValue
import com.carevoice.documentation.application.TranscriptResult
import com.carevoice.domain.common.Either
import com.carevoice.domain.model.BloodPressure
import com.carevoice.domain.model.BodyTemperature
import com.carevoice.domain.model.Confidence
import com.carevoice.domain.model.ExtractionSource
import com.carevoice.domain.model.Observation
import com.carevoice.domain.model.ObservationId
import com.carevoice.domain.model.ObservationStatus
import com.carevoice.domain.model.Pulse
import com.carevoice.domain.model.ResidentId
import com.carevoice.domain.model.ValidationError
import com.carevoice.domain.model.VitalSign
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * Mapper pattern: the JSON shape of observations stored by the adapters (the transcript's `response_body` column and
 * the Redis SSE fan-out message). Kept separate from the HTTP DTOs on purpose: the API may evolve its wire format
 * without breaking rows already stored or messages in flight between replicas of different versions.
 */
@Serializable
internal data class StoredObservation(
    val id: String,
    val tenantId: String,
    val wardId: String,
    val residentId: String,
    val transcriptId: String,
    val vital: StoredVital,
    val source: String,
    val confidence: Double,
    val status: String,
    val recordedAt: String,
    val confirmedBy: String? = null,
    val confirmedAt: String? = null,
    val version: Int,
)

@Serializable
internal sealed interface StoredVital {
    @Serializable
    @SerialName("BLOOD_PRESSURE")
    data class BloodPressureValue(val systolic: Int, val diastolic: Int) : StoredVital

    @Serializable
    @SerialName("PULSE")
    data class PulseValue(val bpm: Int) : StoredVital

    @Serializable
    @SerialName("BODY_TEMPERATURE")
    data class BodyTemperatureValue(val celsius: Double) : StoredVital
}

@Serializable
internal data class StoredRejected(val matchedText: String, val reason: String)

@Serializable
internal data class StoredTranscriptResult(
    val transcriptId: String,
    val residentId: String,
    val observations: List<StoredObservation>,
    val rejected: List<StoredRejected>,
    val usedAiFallback: Boolean,
)

internal val StorageJson: Json =
    Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

internal fun ObservationRecord.toStored(): StoredObservation = StoredObservation(
    id = id.toString(),
    tenantId = tenantId,
    wardId = wardId,
    residentId = observation.residentId.value,
    transcriptId = transcriptId.toString(),
    vital = observation.vital.toStored(),
    source = observation.source.name,
    confidence = observation.confidence.value,
    status = observation.status.name,
    recordedAt = recordedAt.toString(),
    confirmedBy = confirmedBy,
    confirmedAt = confirmedAt?.toString(),
    version = version,
)

internal fun StoredObservation.toRecord(): ObservationRecord = ObservationRecord(
    observation =
    Observation(
        id = ObservationId(UUID.fromString(id)),
        residentId = ResidentId.of(residentId).orThrow("residentId"),
        vital = vital.toDomain(),
        recordedAt = Instant.parse(recordedAt),
        source = ExtractionSource.valueOf(source),
        confidence = Confidence(confidence),
        status = ObservationStatus.valueOf(status),
    ),
    tenantId = tenantId,
    wardId = wardId,
    transcriptId = UUID.fromString(transcriptId),
    confirmedBy = confirmedBy,
    confirmedAt = confirmedAt?.let(Instant::parse),
    version = version,
)

internal fun TranscriptResult.toStored(): StoredTranscriptResult = StoredTranscriptResult(
    transcriptId = transcriptId.toString(),
    residentId = residentId,
    observations = observations.map { it.toStored() },
    rejected = rejected.map { StoredRejected(it.matchedText, it.reason) },
    usedAiFallback = usedAiFallback,
)

internal fun StoredTranscriptResult.toDomain(): TranscriptResult = TranscriptResult(
    transcriptId = UUID.fromString(transcriptId),
    residentId = residentId,
    observations = observations.map { it.toRecord() },
    rejected = rejected.map { RejectedValue(it.matchedText, it.reason) },
    usedAiFallback = usedAiFallback,
)

internal fun VitalSign.toStored(): StoredVital = when (this) {
    is BloodPressure -> StoredVital.BloodPressureValue(systolic, diastolic)
    is Pulse -> StoredVital.PulseValue(bpm)
    is BodyTemperature -> StoredVital.BodyTemperatureValue(celsius)
}

/**
 * Stored values went through the smart constructors when they were written, so they are valid; going through them
 * again on read keeps "an invalid VitalSign cannot exist" true even for a row edited by hand.
 */
internal fun StoredVital.toDomain(): VitalSign = when (this) {
    is StoredVital.BloodPressureValue -> BloodPressure.of(systolic, diastolic).orThrow("blood pressure")
    is StoredVital.PulseValue -> Pulse.of(bpm).orThrow("pulse")
    is StoredVital.BodyTemperatureValue -> BodyTemperature.ofCelsius(celsius).orThrow("temperature")
}

internal fun <T> Either<ValidationError, T>.orThrow(what: String): T = when (this) {
    is Either.Left -> throw IllegalStateException("Stored $what is invalid: ${value.message}")
    is Either.Right -> value
}
