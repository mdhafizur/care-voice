package com.carevoice.alert.api

import com.carevoice.alert.application.FieldViolation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** RFC 9457 problem details, the body of every error response. */
@Serializable
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val requestId: String,
    val errors: List<FieldErrorDto>? = null,
)

@Serializable
data class FieldErrorDto(val field: String, val message: String)

/** Start of the RFC 9457 `type` URI; the problem slug is added to it (docs/CONTRACTS.md, section 6). */
private const val PROBLEM_BASE = "https://carevoice.dev/problems/"

val ProblemContentType: ContentType = ContentType("application", "problem+json")

private val problemJson = Json { explicitNulls = false }

/**
 * Set when a handler already wrote a problem body. StatusPages' status handlers (404, 429) only fill in bodies for bare
 * status responses; without this marker they would also replace our specific 404 problems with the generic one.
 */
private val ProblemWritten = AttributeKey<Unit>("ProblemWritten")

/** Writes an RFC 9457 body; every error response in the service goes through here. */
suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    slug: String,
    title: String,
    detail: String,
    violations: List<FieldViolation> = emptyList(),
) {
    // Step 1: build the problem body from the arguments and the current request (path and request id).
    val problem =
        Problem(
            type = PROBLEM_BASE + slug,
            title = title,
            status = status.value,
            detail = detail,
            instance = request.path(),
            requestId = callId ?: "unknown",
            errors = violations.takeIf { it.isNotEmpty() }?.map { FieldErrorDto(it.field, it.message) },
        )
    // Step 2: mark the call as answered, so the 404 and 429 handlers of StatusPages.kt do not write a second body.
    attributes.put(ProblemWritten, Unit)
    // Step 3: send the body as JSON with the problem content type and the given status.
    respondText(problemJson.encodeToString(Problem.serializer(), problem), ProblemContentType, status)
}

/** True when a handler already wrote a problem body for this call (see [ProblemWritten]). */
internal fun ApplicationCall.hasProblemBody(): Boolean = attributes.contains(ProblemWritten)
