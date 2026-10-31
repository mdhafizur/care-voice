package com.carevoice.alert.api

import com.carevoice.alert.application.AlertServiceException
import com.carevoice.alert.application.FieldViolation
import com.carevoice.alert.logging.event
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

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

/**
 * Errors that only exist at the HTTP boundary (header and media-type checks, roles, overload). Business errors are
 * [AlertServiceException]s from the application layer; both are mapped in [installStatusPages].
 */
sealed class ApiException(message: String) : RuntimeException(message) {
    class BadRequest(val detail: String, val violations: List<FieldViolation> = emptyList()) : ApiException(detail)

    class Forbidden(val detail: String) : ApiException(detail)

    class UnsupportedMediaType : ApiException("The request body must be application/json.")

    class PayloadTooLarge(val limitBytes: Long) : ApiException("The request body must not exceed $limitBytes bytes.")

    /** The event-processing semaphore is exhausted (backpressure). */
    class Overloaded : ApiException("The service is busy. Retry after the time given in Retry-After.")
}

private const val PROBLEM_BASE = "https://carevoice.dev/problems/"
private const val UNEXPECTED_ERROR = "An unexpected error occurred."
private const val RETRY_AFTER_SECONDS = "1"

val ProblemContentType: ContentType = ContentType("application", "problem+json")

private val problemJson = Json { explicitNulls = false }

/**
 * Set when a handler already wrote a problem body. StatusPages' status handlers (404, 429) only fill in bodies for bare
 * status responses; without this marker they would also replace our specific 404 problems with the generic one.
 */
private val ProblemWritten = AttributeKey<Unit>("ProblemWritten")
private val log = LoggerFactory.getLogger("com.carevoice.alert.api.Problems")

/**
 * One INFO line per rejected request (docs/CONTRACTS.md, section 10: 4xx are INFO), with the reason as a field. Only
 * our own wording and field names are logged, never the request body or a parser message that could echo it.
 */
private fun rejected(reason: String, vararg fields: Pair<String, Any?>) =
    log.event(Level.INFO, null, "Request rejected", "reason" to reason, *fields)

/** Writes an RFC 9457 body; every error response in the service goes through here. */
suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    slug: String,
    title: String,
    detail: String,
    violations: List<FieldViolation> = emptyList(),
) {
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
    attributes.put(ProblemWritten, Unit)
    respondText(problemJson.encodeToString(Problem.serializer(), problem), ProblemContentType, status)
}

/**
 * Maps every exception to a problem response. The `when` over [AlertServiceException] is exhaustive (sealed), so a new
 * business error does not compile until it has a status code. 4xx are logged at INFO, 5xx at ERROR, and 5xx bodies
 * never carry exception messages.
 */
fun Application.installStatusPages() {
    install(StatusPages) {
        exception<AlertServiceException> { call, cause -> call.respondServiceError(cause) }
        exception<ApiException> { call, cause -> call.respondApiError(cause) }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respondApiError(ApiException.UnsupportedMediaType())
        }
        exception<ContentTransformationException> { call, _ ->
            call.respondApiError(ApiException.BadRequest("The request body is malformed."))
        }
        exception<BadRequestException> { call, _ ->
            call.respondApiError(ApiException.BadRequest("The request is malformed."))
        }
        exception<Throwable> { call, cause ->
            log.event(Level.ERROR, null, "Unhandled error", "path" to call.request.path(), cause = cause)
            call.respondProblem(
                HttpStatusCode.InternalServerError,
                "internal-error",
                "Internal server error",
                UNEXPECTED_ERROR,
            )
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            if (call.attributes.contains(ProblemWritten)) return@status
            if (call.response.headers[HttpHeaders.RetryAfter] == null) {
                call.response.header(HttpHeaders.RetryAfter, "60")
            }
            // A rate-limit hit is WARN ("handled but suspicious"), other rejections INFO.
            log.event(
                Level.WARN,
                null,
                "Request rejected",
                "reason" to "too-many-requests",
                "path" to call.request.path(),
            )
            call.respondProblem(
                status,
                "too-many-requests",
                "Too many requests",
                "The rate limit is exhausted. Retry after the time given in Retry-After.",
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            if (call.attributes.contains(ProblemWritten)) return@status
            call.respondProblem(status, "not-found", "Not found", "The requested resource does not exist.")
        }
    }
}

private suspend fun ApplicationCall.respondServiceError(cause: AlertServiceException) {
    when (cause) {
        is AlertServiceException.AlertNotFound -> {
            rejected("not-found")
            respondProblem(HttpStatusCode.NotFound, "not-found", "Not found", cause.message!!)
        }

        is AlertServiceException.AlreadyAcknowledged -> {
            rejected("already-acknowledged")
            respondProblem(HttpStatusCode.Conflict, "already-acknowledged", "Already acknowledged", cause.message!!)
        }

        is AlertServiceException.PreconditionRequired -> {
            rejected("precondition-required")
            respondProblem(
                HttpStatusCode(428, "Precondition Required"),
                "precondition-required",
                "Precondition required",
                cause.message!!,
            )
        }

        is AlertServiceException.PreconditionFailed -> {
            rejected("precondition-failed")
            respondProblem(
                HttpStatusCode.PreconditionFailed,
                "precondition-failed",
                "Precondition failed",
                cause.message!!,
            )
        }

        is AlertServiceException.InvalidCursor -> {
            rejected("invalid-cursor")
            respondProblem(
                HttpStatusCode.BadRequest,
                "validation-failed",
                "Validation failed",
                "One or more request parameters are invalid.",
                listOf(FieldViolation("cursor", "is malformed")),
            )
        }

        is AlertServiceException.MalformedEvent -> {
            rejected("malformed-event")
            respondProblem(HttpStatusCode.BadRequest, "malformed-request", "Malformed request", cause.message!!)
        }

        is AlertServiceException.InvalidEvent -> {
            rejected("invalid-event", "fields" to cause.violations.map { it.field })
            respondProblem(
                HttpStatusCode.UnprocessableEntity,
                "invalid-event",
                "Invalid event",
                cause.message!!,
                cause.violations,
            )
        }

        is AlertServiceException.UnsupportedSchemaVersion -> {
            rejected("unsupported-schema-version", "schemaVersion" to cause.version)
            respondProblem(
                HttpStatusCode.UnprocessableEntity,
                "unsupported-schema-version",
                "Unsupported schema version",
                cause.message!!,
            )
        }

        is AlertServiceException.DependencyUnavailable -> {
            log.event(Level.ERROR, null, "Dependency unavailable", "dependency" to cause.dependency, cause = cause)
            response.header(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS)
            respondProblem(
                HttpStatusCode.ServiceUnavailable,
                "service-unavailable",
                "Service unavailable",
                "The service is temporarily unable to handle the request. Retry after the time given in Retry-After.",
            )
        }
    }
}

private suspend fun ApplicationCall.respondApiError(cause: ApiException) {
    when (cause) {
        is ApiException.BadRequest -> {
            rejected("bad-request", "detail" to cause.detail)
            if (cause.violations.isEmpty()) {
                respondProblem(HttpStatusCode.BadRequest, "malformed-request", "Malformed request", cause.detail)
            } else {
                respondProblem(
                    HttpStatusCode.BadRequest,
                    "validation-failed",
                    "Validation failed",
                    cause.detail,
                    cause.violations,
                )
            }
        }

        is ApiException.Forbidden -> {
            rejected("forbidden", "detail" to cause.detail)
            respondProblem(HttpStatusCode.Forbidden, "forbidden", "Forbidden", cause.detail)
        }

        is ApiException.UnsupportedMediaType -> {
            rejected("unsupported-media-type")
            respondProblem(
                HttpStatusCode.UnsupportedMediaType,
                "unsupported-media-type",
                "Unsupported media type",
                cause.message!!,
            )
        }

        is ApiException.PayloadTooLarge -> {
            rejected("payload-too-large")
            respondProblem(HttpStatusCode.PayloadTooLarge, "payload-too-large", "Payload too large", cause.message!!)
        }

        is ApiException.Overloaded -> {
            log.event(Level.WARN, null, "Request rejected: event processing saturated", "reason" to "overloaded")
            response.header(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS)
            respondProblem(
                HttpStatusCode.ServiceUnavailable,
                "service-unavailable",
                "Service unavailable",
                cause.message!!,
            )
        }
    }
}
