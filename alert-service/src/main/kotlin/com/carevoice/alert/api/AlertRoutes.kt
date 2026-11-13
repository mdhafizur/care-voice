package com.carevoice.alert.api

import com.carevoice.alert.application.FieldViolation
import com.carevoice.alert.application.ListAlerts
import com.carevoice.alert.domain.AlertStatus
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch

/**
 * `/v1/alerts`: list, get one, get guidance, and acknowledge. Every lookup stays inside the caller's ward.
 * The list checks `status` and `limit` and answers 400 with all invalid fields at once.
 * List, get and acknowledge send an ETag (a version tag of the body), so clients can check for changes cheaply.
 */
internal fun Route.alertRoutes(api: AlertApi) {
    // List alerts.
    get {
        // Step 1: check `status` and `limit`; one 400 answer lists every invalid parameter.
        val (status, limit) = call.listParameters()

        // Step 2: load one page of alerts from the caller's ward.
        val page = api.listAlerts.execute(call.caller.scope, status, limit, call.request.queryParameters["cursor"])
        val json = ApiJson.encodeToString(AlertPageDto.serializer(), AlertDtoMapper.toDto(page))
        // Step 3: add the total count and the paging links (`Link` header), then send the page with an ETag.
        call.response.header("X-Total-Count", page.totalCount.toString())
        call.response.header(HttpHeaders.Link, linkHeader(status, limit, page.nextCursor, page.prevCursor))
        call.respondJsonWithETag(json, weakETagOf(json))
    }

    // Get one alert.
    get("/{alertId}") {
        val alert = api.getAlert.execute(call.caller.scope, call.alertId())
        val json = ApiJson.encodeToString(AlertDto.serializer(), AlertDtoMapper.toDto(alert))
        call.respondJsonWithETag(json, versionETag(alert.version))
    }

    // Advisory protocol guidance (RAG, ADR-0023). Not cached with an ETag: generated text may differ between calls.
    get("/{alertId}/guidance") {
        // Step 1: load the alert in the caller's scope and generate guidance from the care protocols.
        val guidance = api.getAlertGuidance.execute(call.caller.scope, call.alertId())
        // Step 2: answer as JSON, without an ETag.
        call.respondText(
            ApiJson.encodeToString(AlertGuidanceDto.serializer(), AlertDtoMapper.toDto(guidance)),
            ContentType.Application.Json,
        )
    }

    // Acknowledge an alert. `If-Match` must name the version the client saw.
    patch("/{alertId}/acknowledge") {
        // Step 1: run the use case with the caller, the alert id and the version from If-Match (optimistic
        // locking: it only goes through if nobody changed the alert since the client read it).
        val alert =
            api.acknowledgeAlert.execute(
                scope = call.caller.scope,
                alertId = call.alertId(),
                precondition = parseIfMatch(call.request.header(HttpHeaders.IfMatch)),
                acknowledgedBy = call.caller.username,
            )
        // Step 2: answer with the new version as ETag, marked not cacheable because the alert just changed.
        val json = ApiJson.encodeToString(AlertDto.serializer(), AlertDtoMapper.toDto(alert))
        call.respondJsonWithETag(json, versionETag(alert.version), cacheable = false)
    }
}

/** An id that cannot exist is simply not found; no separate 400, so the response reveals nothing about id formats. */
private fun ApplicationCall.alertId(): String = parameters["alertId"].orEmpty()

/** The checked `status` and `limit` query parameters of the alert list. */
private data class ListParameters(val status: AlertStatus?, val limit: Int)

/**
 * Reads `status` and `limit` from the query; `limit` is [ListAlerts.DEFAULT_LIMIT] when it is not sent.
 * Every invalid value is collected first, so one 400 answer lists them all.
 */
private fun ApplicationCall.listParameters(): ListParameters {
    // Step 1: read both values. Null means the value was not sent, or could not be read.
    val rawStatus = request.queryParameters["status"]
    val rawLimit = request.queryParameters["limit"]
    val status = rawStatus?.let { raw -> AlertStatus.entries.firstOrNull { it.name == raw } }
    val limit = rawLimit?.toIntOrNull()?.takeIf { it in ListAlerts.MIN_LIMIT..ListAlerts.MAX_LIMIT }
    // Step 2: a value that was sent but could not be read is a violation.
    val violations = mutableListOf<FieldViolation>()
    if (rawStatus != null && status == null) {
        violations += FieldViolation("status", "must be one of ${AlertStatus.entries}")
    }
    if (rawLimit != null && limit == null) violations += FieldViolation("limit", LIMIT_MESSAGE)
    // Step 3: answer 400 with every violation, or return the values.
    if (violations.isNotEmpty()) {
        throw ApiException.BadRequest("One or more request parameters are invalid.", violations)
    }
    return ListParameters(status, limit ?: ListAlerts.DEFAULT_LIMIT)
}

private const val LIMIT_MESSAGE = "must be an integer between ${ListAlerts.MIN_LIMIT} and ${ListAlerts.MAX_LIMIT}"
