package com.carevoice.alert.application

/** One invalid input field, reported back as `errors[]` in the problem body. */
data class FieldViolation(val field: String, val message: String)

/**
 * Every business failure a use case can signal. Sealed, so the HTTP error mapping (StatusPages) and the AMQP consumer
 * handle each case with an exhaustive `when` — a new error type does not compile until both decide what it means.
 */
sealed class AlertServiceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** The alert does not exist IN THE CALLER'S WARD. Deliberately the same for "exists elsewhere" (404, not 403). */
    class AlertNotFound(val alertId: String) : AlertServiceException("Alert $alertId was not found.")

    class AlreadyAcknowledged(val alertId: String) : AlertServiceException("Alert $alertId is already acknowledged.")

    /** The update carried no `If-Match` (428). */
    class PreconditionRequired : AlertServiceException("The If-Match header is required to update this resource.")

    /** `If-Match` does not match the current version (412). */
    class PreconditionFailed(val alertId: String) :
        AlertServiceException("The alert was modified by someone else. Fetch it again and retry.")

    /** The event body is not valid JSON or does not match the event structure (400 on HTTP, dead-lettered on AMQP). */
    class MalformedEvent(reason: String) : AlertServiceException("The event is malformed: $reason")

    class InvalidCursor(reason: String) : AlertServiceException("The cursor is malformed: $reason")

    /** Well-formed event with semantically invalid values (422 on HTTP, dead-lettered on AMQP). */
    class InvalidEvent(val violations: List<FieldViolation>) :
        AlertServiceException("The event contains invalid values.")

    class UnsupportedSchemaVersion(val version: Int, val supported: Set<Int>) :
        AlertServiceException("schemaVersion $version is not supported; supported versions are ${supported.sorted()}.")

    /**
     * A dependency (MongoDB) is temporarily unreachable: no primary during a failover, a server-selection timeout or a
     * socket error. Adapters translate driver exceptions into this, so callers react without knowing the driver:
     * HTTP answers 503 + Retry-After, the AMQP consumer retries via the retry queue.
     */
    class DependencyUnavailable(val dependency: String, cause: Throwable) :
        AlertServiceException("$dependency is temporarily unavailable.", cause)
}
