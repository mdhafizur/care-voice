package com.carevoice.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Published by documentation-service (via its transactional outbox) for every stored observation,
 * consumed by alert-service at `POST /v1/events/observations`.
 *
 * Wire rules:
 * - Delivery is at-least-once, so [eventId] is the idempotency key on the consumer side.
 * - Fields are only ever ADDED (with defaults); renaming or removing one is a breaking change that needs
 *   a new [SCHEMA_VERSION] and a new event type.
 * - No personal data: ids only, never names or transcript text.
 */
@Serializable
data class ObservationRecordedEvent(
    val eventId: String,
    val schemaVersion: Int = SCHEMA_VERSION,
    /** ISO-8601 instant, UTC. */
    val occurredAt: String,
    val tenantId: String,
    val wardId: String,
    val residentId: String,
    val observationId: String,
    val vital: VitalPayload,
    /** "RULES" or "AI". */
    val source: String,
    /** "CONFIRMED" or "NEEDS_REVIEW". */
    val status: String,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val TYPE = "observation.recorded"
    }
}

/**
 * One vital sign. A sealed class with a "type" discriminator, so the JSON looks like
 * `{"type":"BLOOD_PRESSURE","systolic":120,"diastolic":75}`.
 */
@Serializable
sealed interface VitalPayload {
    @Serializable
    @SerialName("BLOOD_PRESSURE")
    data class BloodPressure(val systolic: Int, val diastolic: Int) : VitalPayload

    @Serializable
    @SerialName("PULSE")
    data class Pulse(val bpm: Int) : VitalPayload

    @Serializable
    @SerialName("BODY_TEMPERATURE")
    data class BodyTemperature(val celsius: Double) : VitalPayload
}

/** One shared configuration so producer and consumer encode the event identically. */
val ContractJson: Json =
    Json {
        classDiscriminator = "type"
        encodeDefaults = true
        // Consumers tolerate fields added by newer producers (forward compatibility).
        ignoreUnknownKeys = true
        explicitNulls = false
    }
