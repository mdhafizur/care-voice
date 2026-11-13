package com.carevoice.documentation.api

import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.application.FieldError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import kotlin.math.ceil

/**
 * An error raised by the HTTP layer itself (wrong content type, oversized body, missing header), as opposed to the
 * use cases' [ApplicationError]s.
 */
class ApiException(
    val status: HttpStatusCode,
    val slug: String,
    val title: String,
    val detail: String,
    val errors: List<FieldError> = emptyList(),
) : RuntimeException(detail)

object Problems {
    const val TYPE_BASE = "https://carevoice.dev/problems/"
    val CONTENT_TYPE: ContentType = ContentType("application", "problem+json")
    const val GENERIC_5XX = "An unexpected error occurred."

    /** 415 error: the request body is not sent as `application/json`. */
    fun unsupportedMediaType(): ApiException = ApiException(
        HttpStatusCode.UnsupportedMediaType,
        "unsupported-media-type",
        "Unsupported Media Type",
        "The request body must be sent as application/json.",
    )

    /** 413 error: the request body is bigger than [limitBytes]. */
    fun payloadTooLarge(limitBytes: Long): ApiException = ApiException(
        HttpStatusCode.PayloadTooLarge,
        "payload-too-large",
        "Payload Too Large",
        "The request body must not exceed $limitBytes bytes.",
    )

    /** 400 error raised by the HTTP layer, optionally with field-level [errors]. */
    fun badRequest(detail: String, errors: List<FieldError> = emptyList()): ApiException =
        ApiException(HttpStatusCode.BadRequest, "bad-request", "Bad Request", detail, errors)
}

/** Writes an RFC 9457 problem details response. Never includes exception messages for 5xx. */
suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    slug: String,
    title: String,
    detail: String,
    errors: List<FieldError> = emptyList(),
) {
    val body =
        ProblemDetails(
            type = Problems.TYPE_BASE + slug,
            title = title,
            status = status.value,
            detail = detail,
            instance = request.uri.substringBefore('?'),
            requestId = callId,
            errors = errors.takeIf { it.isNotEmpty() }?.map { it.toDto() },
        )
    respondText(ProblemJson.encodeToString(ProblemDetails.serializer(), body), Problems.CONTENT_TYPE, status)
}

/** HTTP 428 Precondition Required (RFC 6585, section 3): an update was sent without an `If-Match` header. */
val PRECONDITION_REQUIRED = HttpStatusCode(428, "Precondition Required")

/** `WWW-Authenticate` value on a 401: the client must send a bearer token (RFC 6750, section 3). */
const val BEARER_CHALLENGE = "Bearer realm=\"carevoice\""

/** `Retry-After` in whole seconds, at least 1 (rounding down to 0 would invite an immediate retry storm). */
fun retryAfterSeconds(millis: Long): String = ceil(millis / 1000.0).toLong().coerceAtLeast(1).toString()
