package com.carevoice.documentation.api

import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.application.FieldError
import com.carevoice.documentation.application.ObservationFilter
import com.carevoice.documentation.application.ObservationRecord
import com.carevoice.documentation.application.PageCursor
import com.carevoice.documentation.application.PageLimits
import com.carevoice.documentation.application.PageRequest
import com.carevoice.documentation.application.Resident
import com.carevoice.documentation.application.TranscriptResult
import com.carevoice.domain.model.BloodPressure
import com.carevoice.domain.model.BodyTemperature
import com.carevoice.domain.model.ObservationStatus
import com.carevoice.domain.model.Pulse
import com.carevoice.domain.model.VitalSign
import com.carevoice.domain.model.VitalType
import java.time.Instant
import java.time.format.DateTimeParseException

/* Mapper pattern: explicit functions between application types and DTOs, in both directions. */

/** The public view of a resident: id, ward and display name only. */
fun Resident.toResponse(): ResidentResponse = ResidentResponse(id = id, wardId = wardId, displayName = displayName)

/** Flattens the stored record for the API: enums become their names and times become ISO-8601 strings. */
fun ObservationRecord.toDto(): ObservationDto = ObservationDto(
    id = id.toString(),
    residentId = observation.residentId.value,
    vital = observation.vital.toDto(),
    source = observation.source.name,
    confidence = observation.confidence.value,
    status = observation.status.name,
    recordedAt = recordedAt.toString(),
    confirmedBy = confirmedBy,
    confirmedAt = confirmedAt?.toString(),
    version = version,
)

/** Maps each kind of vital sign to its own JSON shape (the `type` field tells them apart). */
fun VitalSign.toDto(): VitalDto = when (this) {
    is BloodPressure -> VitalDto.BloodPressure(systolic, diastolic)
    is Pulse -> VitalDto.Pulse(bpm)
    is BodyTemperature -> VitalDto.BodyTemperature(celsius)
}

/** The response for a submitted transcript: the saved observations, the rejected matches, and the AI flag. */
fun TranscriptResult.toResponse(): TranscriptResponse = TranscriptResponse(
    transcriptId = transcriptId.toString(),
    residentId = residentId,
    observations = observations.map { it.toDto() },
    rejected = rejected.map { RejectedDto(it.matchedText, it.reason) },
    usedAiFallback = usedAiFallback,
)

/** Copies a validation error into its JSON form. */
fun FieldError.toDto(): FieldErrorDto = FieldErrorDto(field, message)

/** `?limit=` and `?cursor=` → [PageRequest]; anything malformed is a 400 with field errors. */
fun pageRequestOf(limit: String?, cursor: String?): PageRequest {
    val errors = mutableListOf<FieldError>()
    val parsedLimit =
        when {
            limit == null -> PageLimits.DEFAULT

            else ->
                limit.toIntOrNull()?.takeIf { it in PageLimits.MIN..PageLimits.MAX } ?: run {
                    errors += FieldError("limit", "must be an integer between ${PageLimits.MIN} and ${PageLimits.MAX}")
                    PageLimits.DEFAULT
                }
        }
    val parsedCursor =
        cursor?.let {
            PageCursor.decode(it) ?: run {
                errors += FieldError("cursor", "is malformed")
                null
            }
        }
    if (errors.isNotEmpty()) throw ApplicationError.BadRequest("The request has invalid parameters.", errors)
    return PageRequest(parsedLimit, parsedCursor)
}

/**
 * Filter DTO → [ObservationFilter] + [PageRequest]. Wrong values (unknown vital type, unparsable instant) are 400:
 * the request is malformed. `recordedFrom` after `recordedTo` is well-formed but semantically invalid, which is the
 * use case's 422.
 */
fun ObservationFilterRequest.toQuery(): Pair<ObservationFilter, PageRequest> {
    val errors = mutableListOf<FieldError>()
    val types =
        vitalTypes.orEmpty().mapNotNull { raw ->
            VitalType.entries.firstOrNull { it.name == raw } ?: run {
                errors += FieldError("vitalTypes", "contains an unknown vital type '$raw'")
                null
            }
        }
    val parsedStatus =
        status?.let { raw ->
            ObservationStatus.entries.firstOrNull { it.name == raw } ?: run {
                errors += FieldError("status", "must be one of ${ObservationStatus.entries.joinToString()}")
                null
            }
        }
    val residents = residentIds.orEmpty()
    if (residents.size >
        MAX_FILTER_VALUES
    ) {
        errors += FieldError("residentIds", "must contain at most $MAX_FILTER_VALUES ids")
    }
    if (residents.any { !RESIDENT_ID.matches(it) }) {
        errors +=
            FieldError("residentIds", "contains a malformed resident id")
    }
    val from = instantOrError("recordedFrom", recordedFrom, errors)
    val to = instantOrError("recordedTo", recordedTo, errors)
    val page =
        try {
            pageRequestOf(limit?.toString(), cursor)
        } catch (e: ApplicationError.BadRequest) {
            errors += e.errors
            PageRequest()
        }
    if (errors.isNotEmpty()) throw ApplicationError.BadRequest("The filter is invalid.", errors)
    return ObservationFilter(residents.toSet(), types.toSet(), parsedStatus, from, to) to page
}

/** Parses [raw] as an ISO-8601 instant. A bad value adds a field error and gives null; null input stays null. */
private fun instantOrError(field: String, raw: String?, errors: MutableList<FieldError>): Instant? = raw?.let {
    try {
        Instant.parse(it)
    } catch (_: DateTimeParseException) {
        errors += FieldError(field, "must be an ISO-8601 instant, e.g. 2026-10-25T10:00:00Z")
        null
    }
}

private const val MAX_FILTER_VALUES = 100
private val RESIDENT_ID = Regex("^[A-Za-z0-9-]{1,64}$")
