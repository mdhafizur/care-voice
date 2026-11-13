package com.carevoice.alert.api

import com.carevoice.alert.application.AlertServiceException
import com.carevoice.alert.application.FieldViolation

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
