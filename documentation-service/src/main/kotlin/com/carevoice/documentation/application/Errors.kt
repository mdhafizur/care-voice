package com.carevoice.documentation.application

import kotlin.time.Duration

/** One field-level problem, rendered as `errors[]` in the problem details body. */
data class FieldError(val field: String, val message: String)

/**
 * Every expected failure a use case can report. A sealed hierarchy (not loose exceptions) so the HTTP layer maps it
 * with an exhaustive `when`: adding a case is a compile error until StatusPages handles it.
 *
 * These are exceptions rather than return values because they abort the use case at any depth (including inside a
 * transaction, where throwing is also what triggers the rollback).
 */
sealed class ApplicationError(message: String) : RuntimeException(message) {
    /** 400: the request is malformed (bad JSON, wrong types, missing headers, malformed cursor). */
    class BadRequest(val detail: String, val errors: List<FieldError> = emptyList()) : ApplicationError(detail)

    /** 401: missing/invalid credentials. Wrong user and wrong password are indistinguishable on purpose. */
    class Unauthorized(val detail: String = "Authentication is required.") : ApplicationError(detail)

    /** 403: authenticated, but the role does not allow this operation. */
    class Forbidden(val detail: String = "Your role does not allow this operation.") : ApplicationError(detail)

    /** 404: does not exist OR exists outside the caller's ward (never reveal that it exists). */
    class NotFound(val resource: String, val id: String) : ApplicationError("$resource not found")

    /** 409: the request conflicts with the current state. [slug] selects the problem type. */
    class Conflict(val slug: String, val detail: String) : ApplicationError(detail)

    /** 412: `If-Match` names a version that is no longer current. */
    class PreconditionFailed(
        val detail: String = "The resource was modified by someone else. Reload it and try again.",
    ) : ApplicationError(detail)

    /** 428: an update without `If-Match`. */
    class PreconditionRequired(
        val detail: String = "This request requires an If-Match header with the current ETag.",
    ) : ApplicationError(detail)

    /** 422: well-formed but semantically invalid. */
    class Unprocessable(val detail: String, val errors: List<FieldError> = emptyList()) : ApplicationError(detail)

    /** 429: rate limit exceeded. */
    class TooManyRequests(val retryAfter: Duration) : ApplicationError("Too many requests")

    /** 503: a critical dependency is unavailable; the client should retry after [retryAfter]. */
    class ServiceUnavailable(
        val retryAfter: Duration,
        val detail: String = "The service is temporarily unavailable. Please retry.",
    ) : ApplicationError(detail)
}
