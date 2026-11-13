package com.carevoice.alert.api

import com.carevoice.alert.application.AlertServiceException
import com.carevoice.alert.application.FieldViolation
import com.carevoice.alert.logging.event
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.header
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private const val UNEXPECTED_ERROR = "An unexpected error occurred."

/**
 * `Retry-After` of a plain 503, in seconds: a dependency is down or event processing is full (docs/CONTRACTS.md,
 * sections 6 and 9).
 */
private const val RETRY_AFTER_SECONDS = "1"

/** A request that ran out of time is retried a little later than a plain 503, so a slow dependency can recover. */
private const val DEADLINE_RETRY_AFTER_SECONDS = "2"

/** `Retry-After` of a 429, in seconds, when the rate limiter set none: the per-user limit refills every minute. */
private const val RATE_LIMIT_RETRY_AFTER_SECONDS = "60"

private val log = LoggerFactory.getLogger("com.carevoice.alert.api.Problems")

/**
 * One INFO line per rejected request (docs/CONTRACTS.md, section 10: 4xx are INFO), with the reason as a field. Only
 * our own wording and field names are logged, never the request body or a parser message that could echo it.
 */
private fun rejected(reason: String, vararg fields: Pair<String, Any?>) =
    log.event(Level.INFO, null, "Request rejected", "reason" to reason, *fields)

/**
 * Maps every exception to a problem response. The `when` over [AlertServiceException] is exhaustive (sealed), so a new
 * business error does not compile until it has a status code. 4xx are logged at INFO, 5xx at ERROR, and 5xx bodies
 * never carry exception messages.
 */
fun Application.installStatusPages() {
    install(StatusPages) {
        // Exceptions: each kind of error is turned into its problem response.
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
        // Bare status codes: give a problem body to a 429 or 404 that no handler has answered yet.
        status(HttpStatusCode.TooManyRequests) { call, status ->
            if (call.hasProblemBody()) return@status
            if (call.response.headers[HttpHeaders.RetryAfter] == null) {
                call.response.header(HttpHeaders.RetryAfter, RATE_LIMIT_RETRY_AFTER_SECONDS)
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
            if (call.hasProblemBody()) return@status
            call.respondProblem(status, "not-found", "Not found", "The requested resource does not exist.")
        }
    }
}

/**
 * Turns a business error into its problem response with the right status code.
 * Client errors are logged at INFO. The 503 answers add `Retry-After`, so the client knows when to try again.
 */
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
                // 428 Precondition Required (RFC 6585, section 3): the update was sent without `If-Match`.
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
            respondServiceUnavailable(
                RETRY_AFTER_SECONDS,
                "The service is temporarily unable to handle the request. Retry after the time given in Retry-After.",
            )
        }

        is AlertServiceException.RequestTimedOut -> {
            // Already logged at WARN by the request deadline, with method, path and the limit.
            respondServiceUnavailable(
                DEADLINE_RETRY_AFTER_SECONDS,
                "The request did not finish in time. Retry after the time given in Retry-After.",
            )
        }
    }
}

/** Turns an HTTP-layer error into its problem response; overload gives 503 with `Retry-After` and a WARN log. */
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
            respondServiceUnavailable(RETRY_AFTER_SECONDS, cause.message!!)
        }
    }
}

/** Answers 503 with `Retry-After` set to [retryAfterSeconds], so the client knows when to try again. */
private suspend fun ApplicationCall.respondServiceUnavailable(retryAfterSeconds: String, detail: String) {
    response.header(HttpHeaders.RetryAfter, retryAfterSeconds)
    respondProblem(HttpStatusCode.ServiceUnavailable, "service-unavailable", "Service unavailable", detail)
}
