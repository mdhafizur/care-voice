package com.carevoice.documentation.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Request and response bodies of the public API (docs/CONTRACTS.md, section 7). These are the ONLY classes serialized
 * at the HTTP boundary; domain and persistence types never leak into a response, so an internal refactoring cannot
 * change the wire format by accident. Mapping happens explicitly in Mappers.kt (Mapper pattern).
 */

/**
 * Strict on input, explicit on output:
 * - `ignoreUnknownKeys = false`: an unknown field is a 400 (a typo like "trancript" must not be silently ignored).
 * - `explicitNulls = true`: `"confirmedBy": null` is part of the documented body, not an absent field.
 */
val ApiJson: Json =
    Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

/** Problem bodies omit absent optional members (`errors`) instead of writing `null`, as RFC 9457 examples do. */
val ProblemJson: Json =
    Json(ApiJson) {
        explicitNulls = false
    }

@Serializable
data class TokenRequest(val username: String, val password: String) {
    override fun toString(): String = "TokenRequest(username=$username, password=***)"
}

@Serializable
data class TokenResponse(val accessToken: String, val tokenType: String = "Bearer", val expiresIn: Long)

@Serializable
data class ResidentResponse(val id: String, val wardId: String, val displayName: String)

@Serializable
data class TranscriptRequest(val transcript: String)

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
data class ObservationDto(
    val id: String,
    val residentId: String,
    val vital: VitalDto,
    val source: String,
    val confidence: Double,
    val status: String,
    val recordedAt: String,
    val confirmedBy: String?,
    val confirmedAt: String?,
    val version: Int,
)

@Serializable
data class RejectedDto(val matchedText: String, val reason: String)

@Serializable
data class TranscriptResponse(
    val transcriptId: String,
    val residentId: String,
    val observations: List<ObservationDto>,
    val rejected: List<RejectedDto>,
    val usedAiFallback: Boolean,
)

@Serializable
data class ObservationListResponse(val items: List<ObservationDto>, val nextCursor: String?)

/** Body of `QUERY /v1/observations` and `POST /v1/observations/search`; every field optional. */
@Serializable
data class ObservationFilterRequest(
    val residentIds: List<String>? = null,
    val vitalTypes: List<String>? = null,
    val status: String? = null,
    val recordedFrom: String? = null,
    val recordedTo: String? = null,
    val limit: Int? = null,
    val cursor: String? = null,
)

@Serializable
data class FieldErrorDto(val field: String, val message: String)

/** RFC 9457 problem details, `application/problem+json`. */
@Serializable
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val requestId: String?,
    val errors: List<FieldErrorDto>? = null,
)

@Serializable
data class HealthResponse(val status: String, val checks: Map<String, String> = emptyMap())
