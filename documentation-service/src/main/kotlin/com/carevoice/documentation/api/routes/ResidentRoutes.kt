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
        // Step 1: who is calling. The JWT plugin already turned the Bearer token into a Caller.
        val caller = call.caller()
        // Step 2: the Idempotency-Key header must be a UUID; missing or malformed gives a 400.
        val key = idempotencyKey(call.request.header(IDEMPOTENCY_KEY_HEADER))
        // Step 3: read the JSON body: wrong Content-Type 415, too big 413, empty 400.
        val body = call.receiveJson(TranscriptRequest.serializer(), deps.http.maxBodyBytes)
        // Step 4: run the use case. Its errors (404, 409, 422, 503) go up to the error plugin in Problems.kt,
        // so this route needs no try/catch.
        val outcome =
            deps.submitTranscript.execute(
                caller,
                SubmitTranscriptCommand(call.parameters["residentId"].orEmpty(), key, body.transcript),
            )
        // Step 5: answer. Location points to the saved transcript. A new note is 201 Created; a retry with
        // the same key is 200 OK plus Idempotent-Replayed: true, because nothing new was saved.
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
        // Step 1: read the page size and the cursor (where the previous page ended); bad values give a 400.
        val page = pageRequestOf(call.request.queryParameters["limit"], call.request.queryParameters["cursor"])
        // Step 2: run the use case; it checks that the caller may see this resident.
        val result = deps.listResidentObservations.execute(call.caller(), call.parameters["residentId"].orEmpty(), page)
        // Step 3: paging headers (e.g. the link to the next page), then the JSON body. The answer is
        // conditional: if the client's If-None-Match ETag still matches, it gets 304 without a body.
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
