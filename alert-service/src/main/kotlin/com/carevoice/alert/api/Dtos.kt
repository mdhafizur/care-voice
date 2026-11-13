package com.carevoice.alert.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON settings of the public API. `explicitNulls = true`, so `acknowledgedBy: null` is present in the body as the
 * OpenAPI schema requires (an absent field and a null field are different contracts).
 */
val ApiJson: Json =
    Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = true
    }

@Serializable
data class EventAcceptedDto(val eventId: String, val alertsCreated: Int)

@Serializable
data class AlertDto(
    val id: String,
    val tenantId: String,
    val wardId: String,
    val residentId: String,
    val observationId: String,
    val eventId: String,
    val rule: String,
    val severity: String,
    val message: String,
    val vital: VitalDto,
    val status: String,
    val observedAt: String,
    val createdAt: String,
    val acknowledgedBy: String?,
    val acknowledgedAt: String?,
    val version: Long,
)

@Serializable
sealed interface VitalDto {
    @Serializable
    @SerialName("BLOOD_PRESSURE")
    data class BloodPressure(val systolic: Int, val diastolic: Int) : VitalDto

    @Serializable
    @SerialName("PULSE")
    data class Pulse(val bpm: Int) : VitalDto

    @Serializable
    @SerialName("BODY_TEMPERATURE")
    data class BodyTemperature(val celsius: Double) : VitalDto
}

@Serializable
data class AlertPageDto(val items: List<AlertDto>, val nextCursor: String?)

@Serializable
data class HealthDto(val status: String, val checks: Map<String, String>)

/** `GET /v1/alerts/{alertId}/guidance`: cited protocol steps for one alert (advisory only). */
@Serializable
data class AlertGuidanceDto(
    val alertId: String,
    val rule: String,
    val status: String,
    val generatedBy: String?,
    val steps: List<GuidanceStepDto>,
    val sources: List<GuidanceSourceDto>,
    val disclaimer: String,
)

@Serializable
data class GuidanceStepDto(val text: String, val citations: List<String>)

@Serializable
data class GuidanceSourceDto(
    val id: String,
    val protocol: String,
    val section: String,
    val score: Double,
    val excerpt: String,
)
