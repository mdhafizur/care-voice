package com.carevoice.documentation.api

import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.application.FieldError
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
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

private val problemLog = LoggerFactory.getLogger("com.carevoice.documentation.api.Problems")

/**
 * One structured line per rejected request: `status` and a stable `reason` slug as fields, so Loki can count e.g. all
 * 412s or all `service-unavailable` without parsing messages. Never the request body or error messages (they can echo
 * transcript text).
 */
private fun ApplicationCall.logRejected(status: Int, reason: String, vararg fields: Pair<String, Any?>) {
    problemLog.event(
        when {
            status >= 500 -> Level.ERROR

            // A rate-limit hit is "handled but suspicious".
            status == HttpStatusCode.TooManyRequests.value -> Level.WARN

            else -> Level.INFO
        },
        LogType.REQUEST,
        "Request rejected",
        "status" to status,
        "reason" to reason,
        // The path is user input: capped so a giant URL cannot bloat the log pipeline.
        "path" to request.path().take(MAX_LOGGED_PATH),
        *fields,
    )
}

/** Longest part of the request path, in characters, that goes into a log line. */
private const val MAX_LOGGED_PATH = 256

/** The stable `reason` slug written to the log for each application error type. */
private fun ApplicationError.logReason(): String = when (this) {
    is ApplicationError.BadRequest, is ApplicationError.Unprocessable -> "validation-error"
    is ApplicationError.Unauthorized -> "unauthorized"
    is ApplicationError.Forbidden -> "forbidden"
    is ApplicationError.NotFound -> "not-found"
    is ApplicationError.Conflict -> slug
    is ApplicationError.PreconditionFailed -> "precondition-failed"
    is ApplicationError.PreconditionRequired -> "precondition-required"
    is ApplicationError.TooManyRequests -> "too-many-requests"
    is ApplicationError.ServiceUnavailable -> "service-unavailable"
}

/**
 * Maps every failure to problem details. The `when` over [ApplicationError] is exhaustive (sealed class), so adding
 * an error type fails compilation until it has a status code here.
 *
 * Logging (docs/CONTRACTS.md, section 10): 4xx at INFO without stack trace, 5xx at ERROR with it. Messages of
 * deserialization errors are never logged or returned: they can echo the request body (transcript text).
 */
fun Application.installProblemDetails() {
    install(StatusPages) {
        // Business errors: one status code per error type, then one log line for all of them.
        exception<ApplicationError> { call, cause ->
            when (cause) {
                is ApplicationError.BadRequest ->
                    call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "validation-error",
                        "Bad Request",
                        cause.detail,
                        cause.errors,
                    )

                is ApplicationError.Unauthorized -> {
                    call.response.header(HttpHeaders.WWWAuthenticate, BEARER_CHALLENGE)
                    call.respondProblem(HttpStatusCode.Unauthorized, "unauthorized", "Unauthorized", cause.detail)
                }

                is ApplicationError.Forbidden ->
                    call.respondProblem(HttpStatusCode.Forbidden, "forbidden", "Forbidden", cause.detail)

                is ApplicationError.NotFound ->
                    call.respondProblem(
                        HttpStatusCode.NotFound,
                        "not-found",
                        "Not Found",
                        "${cause.resource} '${cause.id}' was not found.",
                    )

                is ApplicationError.Conflict ->
                    call.respondProblem(HttpStatusCode.Conflict, cause.slug, "Conflict", cause.detail)

                is ApplicationError.PreconditionFailed ->
                    call.respondProblem(
                        HttpStatusCode.PreconditionFailed,
                        "precondition-failed",
                        "Precondition Failed",
                        cause.detail,
                    )

                is ApplicationError.PreconditionRequired ->
                    call.respondProblem(
                        PRECONDITION_REQUIRED,
                        "precondition-required",
                        "Precondition Required",
                        cause.detail,
                    )

                is ApplicationError.Unprocessable ->
                    call.respondProblem(
                        HttpStatusCode.UnprocessableEntity,
                        "validation-error",
                        "Unprocessable Content",
                        cause.detail,
                        cause.errors,
                    )

                is ApplicationError.TooManyRequests -> {
                    call.response.header(
                        HttpHeaders.RetryAfter,
                        retryAfterSeconds(cause.retryAfter.inWholeMilliseconds),
                    )
                    call.respondProblem(
                        HttpStatusCode.TooManyRequests,
                        "too-many-requests",
                        "Too Many Requests",
                        "Rate limit exceeded. Retry later.",
                    )
                }

                is ApplicationError.ServiceUnavailable -> {
                    call.response.header(
                        HttpHeaders.RetryAfter,
                        retryAfterSeconds(cause.retryAfter.inWholeMilliseconds),
                    )
                    call.respondProblem(
                        HttpStatusCode.ServiceUnavailable,
                        "service-unavailable",
                        "Service Unavailable",
                        cause.detail,
                    )
                }
            }
            call.logRejected(
                call.response.status()?.value ?: 0,
                cause.logReason(),
                // For a 503 the underlying cause class (e.g. PSQLException) says which dependency failed.
                "cause" to (cause.cause?.javaClass?.simpleName),
            )
        }
        // HTTP-layer and parsing errors. Parser messages are never sent back, because they can echo the body.
        exception<ApiException> { call, cause ->
            call.respondProblem(cause.status, cause.slug, cause.title, cause.detail, cause.errors)
            call.logRejected(cause.status.value, cause.slug)
        }
        exception<SerializationException> { call, _ ->
            call.respondProblem(
                HttpStatusCode.BadRequest,
                "malformed-request",
                "Bad Request",
                "The request body is not valid JSON for this endpoint.",
            )
            call.logRejected(HttpStatusCode.BadRequest.value, "malformed-json")
        }
        exception<BadRequestException> { call, _ ->
            call.respondProblem(
                HttpStatusCode.BadRequest,
                "malformed-request",
                "Bad Request",
                "The request is malformed.",
            )
            call.logRejected(HttpStatusCode.BadRequest.value, "malformed-request")
        }
        // Anything else is a 500 with a fixed message. The full error goes only to the log.
        exception<Throwable> { call, cause ->
            problemLog.event(
                Level.ERROR,
                LogType.REQUEST,
                "Unhandled error",
                "path" to call.request.path().take(MAX_LOGGED_PATH),
                cause = cause,
            )
            call.respondProblem(
                HttpStatusCode.InternalServerError,
                "internal-error",
                "Internal Server Error",
                Problems.GENERIC_5XX,
            )
        }
        // Responses produced by plugins without a body (RateLimit's 429, routing's 404/405) also become problem+json.
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respondProblem(status, "too-many-requests", "Too Many Requests", "Rate limit exceeded. Retry later.")
            call.logRejected(status.value, "too-many-requests")
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondProblem(status, "not-found", "Not Found", "No resource exists at this path.")
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondProblem(
                status,
                "method-not-allowed",
                "Method Not Allowed",
                "This method is not supported for this path.",
            )
        }
    }
}

/** HTTP 428 Precondition Required (RFC 6585, section 3): an update was sent without an `If-Match` header. */
val PRECONDITION_REQUIRED = HttpStatusCode(428, "Precondition Required")

/** `WWW-Authenticate` value on a 401: the client must send a bearer token (RFC 6750, section 3). */
const val BEARER_CHALLENGE = "Bearer realm=\"carevoice\""

/** `Retry-After` in whole seconds, at least 1 (rounding down to 0 would invite an immediate retry storm). */
fun retryAfterSeconds(millis: Long): String = ceil(millis / 1000.0).toLong().coerceAtLeast(1).toString()
