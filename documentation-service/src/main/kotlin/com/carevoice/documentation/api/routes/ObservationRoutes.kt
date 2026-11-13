package com.carevoice.documentation.api.routes

import com.carevoice.documentation.api.Dependencies
import com.carevoice.documentation.api.ObservationDto
import com.carevoice.documentation.api.ObservationFilterRequest
import com.carevoice.documentation.api.ObservationListResponse
import com.carevoice.documentation.api.TOTAL_COUNT_HEADER
import com.carevoice.documentation.api.TranscriptResponse
import com.carevoice.documentation.api.ifMatchVersion
import com.carevoice.documentation.api.plugins.caller
import com.carevoice.documentation.api.receiveJson
import com.carevoice.documentation.api.respondJson
import com.carevoice.documentation.api.respondJsonConditional
import com.carevoice.documentation.api.toDto
import com.carevoice.documentation.api.toListResponse
import com.carevoice.documentation.api.toQuery
import com.carevoice.documentation.api.toResponse
import com.carevoice.documentation.api.versionEtag
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.method
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** The HTTP QUERY method (safe, idempotent, with a body; IETF draft "The HTTP QUERY Method"). */
val QUERY: HttpMethod = HttpMethod("QUERY")

/** Transcript and observation endpoints. Must be mounted inside `authenticate(JWT_AUTH)`. */
fun Route.observationRoutes(deps: Dependencies) {
    get("/v1/transcripts/{transcriptId}") {
        val result = deps.getTranscript.execute(call.caller(), call.parameters["transcriptId"].orEmpty())
        call.respondJson(TranscriptResponse.serializer(), result.toResponse())
    }

    // QUERY carries the filter in the body like POST, but is safe and idempotent like GET, so caches and retries
    // treat it correctly. POST /search is the fallback for clients and proxies that do not support QUERY yet.
    route("/v1/observations") {
        method(QUERY) { handle { queryObservations(deps) } }
    }
    post("/v1/observations/search") { queryObservations(deps) }

    patch("/v1/observations/{observationId}/confirm") {
        // Step 1: run the use case with the caller and the version from If-Match (optimistic locking: the update
        // only goes through if nobody changed the observation since the client read it). A missing If-Match
        // gives 428, a version that no longer matches gives 412; the error plugin turns them into answers.
        val updated =
            deps.confirmObservation.execute(
                call.caller(),
                call.parameters["observationId"].orEmpty(),
                call.ifMatchVersion(),
            )
        // Step 2: answer with the new version as ETag, so the client can send it in its next If-Match.
        call.response.header(HttpHeaders.ETag, versionEtag(updated.version))
        call.respondJson(ObservationDto.serializer(), updated.toDto())
    }
}

/** Shared handler for QUERY and POST search: reads the filter body and answers one page, with an ETag. */
private suspend fun RoutingContext.queryObservations(deps: Dependencies) {
    // Step 1: who is calling, then read the filter from the JSON body (415, 413 or 400 when it is wrong).
    val caller = call.caller()
    val request = call.receiveJson(ObservationFilterRequest.serializer(), deps.http.maxBodyBytes)
    // Step 2: turn the body into a filter and a page request, then run the search.
    val (filter, page) = request.toQuery()
    val result = deps.queryObservations.execute(caller, filter, page)
    // The cursor travels in the body (nextCursor), so there is no Link header; the total is still useful.
    call.response.header(TOTAL_COUNT_HEADER, result.totalCount.toString())
    // Step 3: answer with an ETag; a client that still has this page gets 304 without a body.
    call.respondJsonConditional(
        ObservationListResponse.serializer(),
        result.toListResponse(),
    )
}
