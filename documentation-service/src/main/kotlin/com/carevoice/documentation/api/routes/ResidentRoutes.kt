package com.carevoice.documentation.api.routes

import com.carevoice.documentation.api.Dependencies
import com.carevoice.documentation.api.IDEMPOTENCY_KEY_HEADER
import com.carevoice.documentation.api.IDEMPOTENT_REPLAYED_HEADER
import com.carevoice.documentation.api.ObservationListResponse
import com.carevoice.documentation.api.ResidentResponse
import com.carevoice.documentation.api.TranscriptRequest
import com.carevoice.documentation.api.TranscriptResponse
import com.carevoice.documentation.api.headerError
import com.carevoice.documentation.api.pageRequestOf
import com.carevoice.documentation.api.paginationHeaders
import com.carevoice.documentation.api.plugins.caller
import com.carevoice.documentation.api.receiveJson
import com.carevoice.documentation.api.respondJson
import com.carevoice.documentation.api.respondJsonConditional
import com.carevoice.documentation.api.toDto
import com.carevoice.documentation.api.toResponse
import com.carevoice.documentation.application.SubmitTranscriptCommand
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID

/** Resident-scoped endpoints (docs/CONTRACTS.md, section 7). Must be mounted inside `authenticate(JWT_AUTH)`. */
fun Route.residentRoutes(deps: Dependencies) {
    // Read one resident.
    get("/v1/residents/{residentId}") {
        val resident = deps.getResident.execute(call.caller(), call.parameters["residentId"].orEmpty())
        call.respondJsonConditional(ResidentResponse.serializer(), resident.toResponse())
    }

    // Submit a note. A retry with the same idempotency key answers 200 with a "replayed" header;
    // a new note answers 201.
    post("/v1/residents/{residentId}/transcripts") {
        val caller = call.caller()
        val key = idempotencyKey(call.request.header(IDEMPOTENCY_KEY_HEADER))
        val body = call.receiveJson(TranscriptRequest.serializer(), deps.http.maxBodyBytes)
        val outcome =
            deps.submitTranscript.execute(
                caller,
                SubmitTranscriptCommand(call.parameters["residentId"].orEmpty(), key, body.transcript),
            )
        val response = outcome.result.toResponse()
        call.response.header(HttpHeaders.Location, "/v1/transcripts/${response.transcriptId}")
        if (outcome.replayed) {
            call.response.header(IDEMPOTENT_REPLAYED_HEADER, "true")
            call.respondJson(TranscriptResponse.serializer(), response, HttpStatusCode.OK)
        } else {
            call.respondJson(TranscriptResponse.serializer(), response, HttpStatusCode.Created)
        }
    }

    // A resident's observations, one page at a time.
    get("/v1/residents/{residentId}/observations") {
        val page = pageRequestOf(call.request.queryParameters["limit"], call.request.queryParameters["cursor"])
        val result = deps.listResidentObservations.execute(call.caller(), call.parameters["residentId"].orEmpty(), page)
        call.paginationHeaders(result)
        call.respondJsonConditional(
            ObservationListResponse.serializer(),
            ObservationListResponse(result.items.map { it.toDto() }, result.nextCursor),
        )
    }
}

/** The key must be a UUID (docs/CONTRACTS.md, section 7); missing or malformed → 400. */
private fun idempotencyKey(raw: String?): UUID {
    if (raw.isNullOrBlank()) headerError(IDEMPOTENCY_KEY_HEADER, "is required")
    return try {
        UUID.fromString(raw.trim()).takeIf { it.toString() == raw.trim().lowercase() }
    } catch (_: IllegalArgumentException) {
        null
    } ?: headerError(IDEMPOTENCY_KEY_HEADER, "must be a UUID")
}
