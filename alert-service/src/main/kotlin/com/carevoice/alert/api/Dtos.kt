package com.carevoice.alert.api

import com.carevoice.alert.application.AlertGuidance
import com.carevoice.alert.application.AlertPage
import com.carevoice.alert.application.ProcessingResult
import com.carevoice.alert.application.ReadinessReport
import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.Vital
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

/**
 * Mapper pattern: explicit domain → DTO functions. The wire format is decoupled from the domain model, so a domain
 * refactoring cannot change the public API by accident (the contract tests would catch it, this prevents it).
 */
object AlertDtoMapper {
    fun toDto(alert: Alert): AlertDto = AlertDto(
        id = alert.id,
        tenantId = alert.tenantId,
        wardId = alert.wardId,
        residentId = alert.residentId,
        observationId = alert.observationId,
        eventId = alert.eventId,
        rule = alert.rule.name,
        severity = alert.severity.name,
        message = alert.message,
        vital = toDto(alert.vital),
        status = alert.status.name,
        observedAt = alert.observedAt.toString(),
        createdAt = alert.createdAt.toString(),
        acknowledgedBy = alert.acknowledgedBy,
        acknowledgedAt = alert.acknowledgedAt?.toString(),
        version = alert.version,
    )

    fun toDto(vital: Vital): VitalDto = when (vital) {
        is Vital.BloodPressure -> VitalDto.BloodPressure(vital.systolic, vital.diastolic)
        is Vital.Pulse -> VitalDto.Pulse(vital.bpm)
        is Vital.BodyTemperature -> VitalDto.BodyTemperature(vital.celsius)
    }

    fun toDto(page: AlertPage): AlertPageDto = AlertPageDto(page.items.map(::toDto), page.nextCursor)

    fun toDto(result: ProcessingResult): EventAcceptedDto = EventAcceptedDto(result.eventId, result.alertsCreated)

    fun toDto(guidance: AlertGuidance): AlertGuidanceDto = AlertGuidanceDto(
        alertId = guidance.alert.id,
        rule = guidance.alert.rule.name,
        status = guidance.status.name,
        generatedBy = guidance.generatedBy?.name,
        steps = guidance.steps.map { GuidanceStepDto(it.text, it.citations) },
        sources =
        guidance.sources.map { source ->
            GuidanceSourceDto(
                id = source.chunk.id,
                protocol = source.chunk.documentTitle,
                section = source.chunk.section,
                // Three decimals: enough to compare relevance, stable in responses and tests.
                score = Math.round(source.score * 1_000) / 1_000.0,
                // The embedded text starts with "<title>. <section>." for retrieval; the excerpt is the body.
                excerpt = source.chunk.text.substringAfter('\n').trim(),
            )
        },
        disclaimer = GUIDANCE_DISCLAIMER,
    )

    const val GUIDANCE_DISCLAIMER =
        "Advisory only, based on the facility's protocols. " +
            "Clinical judgement and the physician's orders take precedence."

    fun toDto(report: ReadinessReport): HealthDto = HealthDto(
        status = if (report.ready) "UP" else "DOWN",
        checks = report.checks.mapValues { it.value.name },
    )
}
