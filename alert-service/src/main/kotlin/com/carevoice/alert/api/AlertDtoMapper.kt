package com.carevoice.alert.api

import com.carevoice.alert.application.AlertGuidance
import com.carevoice.alert.application.AlertPage
import com.carevoice.alert.application.ProcessingResult
import com.carevoice.alert.application.ReadinessReport
import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.Vital

/**
 * Mapper pattern: explicit domain → DTO functions. The wire format is decoupled from the domain model, so a domain
 * refactoring cannot change the public API by accident (the contract tests would catch it, this prevents it).
 */
object AlertDtoMapper {
    /** Maps an alert to its JSON shape: enums become their names, times become ISO-8601 strings. */
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

    /** Maps each kind of vital sign to its matching DTO subtype. */
    fun toDto(vital: Vital): VitalDto = when (vital) {
        is Vital.BloodPressure -> VitalDto.BloodPressure(vital.systolic, vital.diastolic)
        is Vital.Pulse -> VitalDto.Pulse(vital.bpm)
        is Vital.BodyTemperature -> VitalDto.BodyTemperature(vital.celsius)
    }

    /** Maps one page of alerts and keeps the cursor for the next page. */
    fun toDto(page: AlertPage): AlertPageDto = AlertPageDto(page.items.map(::toDto), page.nextCursor)

    /** Maps the result of processing an event: the event id and how many alerts were created. */
    fun toDto(result: ProcessingResult): EventAcceptedDto = EventAcceptedDto(result.eventId, result.alertsCreated)

    /** Maps protocol guidance to its response; scores are rounded and the fixed advisory disclaimer is added. */
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

    /** Maps a readiness report to UP or DOWN, plus the state of each single check by name. */
    fun toDto(report: ReadinessReport): HealthDto = HealthDto(
        status = if (report.ready) "UP" else "DOWN",
        checks = report.checks.mapValues { it.value.name },
    )
}
