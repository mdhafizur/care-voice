package com.carevoice.alert.application

import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.WardScope
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Use case: one alert of the caller's ward.
 *
 * An alert of another ward is reported exactly like a missing one (404, not 403): a 403 would confirm that the id
 * exists, which leaks information about other wards' residents.
 */
class GetAlert(private val alerts: AlertRepository) {
    private val log = LoggerFactory.getLogger(GetAlert::class.java)

    /** Returns the alert, or throws AlertNotFound when it does not exist or belongs to another ward. */
    suspend fun execute(scope: WardScope, alertId: String): Alert = alerts.findById(scope, alertId) ?: run {
        // Same 404 for "does not exist" and "other ward"; the id is user input, so it is capped.
        log.event(Level.DEBUG, LogType.BUSINESS, "Alert not found in ward", "alertId" to alertId.take(256))
        throw AlertServiceException.AlertNotFound(alertId)
    }
}
