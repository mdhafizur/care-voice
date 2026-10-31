package com.carevoice.alert.domain

import java.time.Instant

enum class Severity { CRITICAL, WARNING }

enum class AlertStatus { OPEN, ACKNOWLEDGED }

/** The caller's tenant and ward: every read and write is restricted to it (docs/CONTRACTS.md, section 5). */
data class WardScope(val tenantId: String, val wardId: String)

/**
 * An alert raised for one observation by one rule.
 *
 * @property eventId the event that produced the alert; together with [rule] it is unique, which is what makes
 *   reprocessing a redelivered event harmless.
 * @property version optimistic-locking counter, exposed as `ETag: "<version>"`; every update increments it.
 */
data class Alert(
    val id: String,
    val tenantId: String,
    val wardId: String,
    val residentId: String,
    val observationId: String,
    val eventId: String,
    val rule: AlertRuleType,
    val severity: Severity,
    val message: String,
    val vital: Vital,
    val status: AlertStatus,
    val observedAt: Instant,
    val createdAt: Instant,
    val acknowledgedBy: String?,
    val acknowledgedAt: Instant?,
    val version: Long,
) {
    val scope: WardScope get() = WardScope(tenantId, wardId)

    val isAcknowledged: Boolean get() = status == AlertStatus.ACKNOWLEDGED

    companion object {
        /** Factory for a new OPEN alert from a rule [finding]. */
        fun open(
            id: String,
            eventId: String,
            observation: Observation,
            finding: AlertFinding,
            createdAt: Instant,
        ): Alert = Alert(
            id = id,
            tenantId = observation.tenantId,
            wardId = observation.wardId,
            residentId = observation.residentId,
            observationId = observation.observationId,
            eventId = eventId,
            rule = finding.rule,
            severity = finding.severity,
            message = finding.message,
            vital = observation.vital,
            status = AlertStatus.OPEN,
            observedAt = observation.observedAt,
            createdAt = createdAt,
            acknowledgedBy = null,
            acknowledgedAt = null,
            version = 0,
        )
    }
}
