package com.carevoice.alert.application

import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.WardScope
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import java.time.temporal.ChronoUnit

/** The parsed `If-Match` header of an update. */
sealed interface VersionPrecondition {
    /** `If-Match: *` — any current version. */
    data object AnyVersion : VersionPrecondition

    /** `If-Match: "3"` (or a list). Unparseable entries are dropped; an empty set never matches. */
    data class OneOf(val versions: Set<Long>) : VersionPrecondition

    /** True for `If-Match: *`; otherwise only when [version] is one of the listed versions. */
    fun matches(version: Long): Boolean = when (this) {
        AnyVersion -> true
        is OneOf -> version in versions
    }
}

/**
 * Use case: a ward lead acknowledges an OPEN alert.
 *
 * Order of checks: existence in the caller's ward (404) → `If-Match` present (428) → version matches (412,
 * evaluated before the state change as RFC 9110 conditional requests require) → still OPEN (409).
 *
 * Optimistic locking: the write is one conditional update `{_id, scope, status: OPEN, version: expected}`; it either
 * applies atomically or not at all. A plain read-then-write would let two leads acknowledge concurrently and the later
 * write would silently overwrite who acknowledged the alert.
 */
class AcknowledgeAlert(private val alerts: AlertRepository, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(AcknowledgeAlert::class.java)

    /**
     * Acknowledges the alert and returns it with its new version; the checks run in the order described above.
     * If a concurrent write wins between our read and our write, [lostRace] picks the matching error.
     */
    suspend fun execute(
        scope: WardScope,
        alertId: String,
        precondition: VersionPrecondition?,
        acknowledgedBy: String,
    ): Alert {
        // Step 1: find the alert in the caller's ward (404 when it is not there).
        val current = alerts.findById(scope, alertId) ?: throw AlertServiceException.AlertNotFound(alertId)
        // Step 2: `If-Match` must be sent (428) and must name the current version (412).
        if (precondition == null) throw AlertServiceException.PreconditionRequired()
        if (!precondition.matches(current.version)) throw AlertServiceException.PreconditionFailed(alertId)
        // Step 3: an alert can be acknowledged only once (409).
        if (current.isAcknowledged) throw AlertServiceException.AlreadyAcknowledged(alertId)

        // Step 4: write with one conditional update. If another lead was faster, `lostRace` picks the error.
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val acknowledged = alerts.acknowledge(scope, alertId, current.version, acknowledgedBy, now)
            ?: throw lostRace(scope, alertId)
        // Step 5: log the business event and return the alert with its new version.
        log.event(
            Level.INFO,
            LogType.BUSINESS,
            "Alert acknowledged",
            "alertId" to alertId,
            "rule" to acknowledged.rule.name,
            "version" to acknowledged.version,
            // Time from alert to acknowledgement: the number a ward lead's response time is judged by.
            "openForMs" to (now.toEpochMilli() - acknowledged.createdAt.toEpochMilli()),
        )
        return acknowledged
    }

    /** The conditional update matched nothing: someone changed the alert between our read and our write. */
    private suspend fun lostRace(scope: WardScope, alertId: String): AlertServiceException {
        val latest = alerts.findById(scope, alertId) ?: return AlertServiceException.AlertNotFound(alertId)
        return if (latest.isAcknowledged) {
            AlertServiceException.AlreadyAcknowledged(alertId)
        } else {
            AlertServiceException.PreconditionFailed(alertId)
        }
    }
}
