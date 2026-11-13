package com.carevoice.documentation.application

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import com.carevoice.domain.model.ObservationStatus
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Use case: a nurse confirms a NEEDS_REVIEW observation (`PATCH /v1/observations/{id}/confirm`).
 *
 * Optimistic locking instead of a "read, check, write" sequence: the update itself carries the version the client
 * saw (`If-Match`), so two nurses confirming at once cannot both win, and nothing holds a lock while a human thinks.
 *
 * Order of checks (docs/CONTRACTS.md, section 5): role (403) → exists in the caller's ward (404) → `If-Match` present
 * (428) → version current (412) → business rule "not already confirmed" (409).
 */
class ConfirmObservation(
    private val observations: ObservationRepository,
    private val audit: AuditLog,
    private val transactions: TransactionRunner,
    private val cacheBus: CacheInvalidationBus,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(ConfirmObservation::class.java)

    /**
     * Confirms the observation and writes an audit entry in one transaction, then clears the ward's cached pages.
     * An id that is not a UUID is a 404. [expectedVersion] is the `If-Match` value; null means the header was missing.
     */
    suspend fun execute(caller: Caller, observationId: String, expectedVersion: Int?): ObservationRecord {
        // Step 1: only logged-in staff may confirm. A malformed id gives the same 404 as an unknown one.
        caller.requireAnyRole()
        val id = parseUuid(observationId) ?: throw ApplicationError.NotFound("Observation", observationId)
        val confirmed =
            transactions.inTransaction {
                // Step 2: load the observation from the caller's ward. One in another ward also gives 404.
                val current =
                    observations.findById(caller.tenantId, caller.wardId, id)
                        ?: throw ApplicationError.NotFound("Observation", observationId)
                // Step 3: the client must send the version it last saw (428) and it must still be the current one
                // (412), so nobody confirms a note that changed after they read it. A note that is already
                // confirmed cannot be confirmed again (409).
                if (expectedVersion == null) throw ApplicationError.PreconditionRequired()
                requireConfirmable(current, observationId, expectedVersion)
                // Step 4: confirm with a conditional update (it changes the row only if the version
                // is still the same) and write the audit row in the same transaction.
                val now = clock.instant()
                when (
                    val outcome = observations.confirm(
                        caller.tenantId,
                        caller.wardId,
                        id,
                        expectedVersion,
                        caller.username,
                        now,
                    )
                ) {
                    is ConfirmOutcome.Confirmed -> {
                        audit.record(
                            AuditEntry(
                                tenantId = caller.tenantId,
                                actor = caller.username,
                                action = "OBSERVATION_CONFIRMED",
                                entityType = "observation",
                                entityId = id.toString(),
                                occurredAt = now,
                                details = mapOf("version" to outcome.record.version.toString()),
                            ),
                        )
                        outcome.record
                    }

                    ConfirmOutcome.VersionMismatch -> {
                        // Read said the version matched, the conditional UPDATE did not: a concurrent confirm won.
                        log.event(
                            Level.DEBUG,
                            LogType.BUSINESS,
                            "Confirm lost a race: version changed during the update",
                            "observationId" to observationId,
                            "expectedVersion" to expectedVersion,
                        )
                        throw ApplicationError.PreconditionFailed()
                    }

                    ConfirmOutcome.NotFound -> throw ApplicationError.NotFound("Observation", observationId)
                }
            }
        // Step 5: after the commit, log the result and tell the cache to drop this ward's pages.
        log.event(
            Level.INFO,
            LogType.BUSINESS,
            "Observation confirmed",
            "observationId" to observationId,
            "version" to confirmed.version,
        )
        // After commit: cached query pages of this ward still show NEEDS_REVIEW.
        // Best effort: the confirmation is committed; a missed invalidation only means up to 10 s of stale cache.
        try {
            cacheBus.publish(CacheInvalidation.ObservationQuery(caller.tenantId, caller.wardId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The bus adapter already logged and counted the failure.
        }
        return confirmed
    }

    /**
     * Throws when [current] may not be confirmed: 412 when its version is no longer [expectedVersion], 409 when it is
     * already confirmed. The version is checked first, as the class comment says.
     */
    private fun requireConfirmable(current: ObservationRecord, observationId: String, expectedVersion: Int) {
        if (current.version != expectedVersion) {
            log.event(
                Level.DEBUG,
                LogType.BUSINESS,
                "Confirm refused: stale version",
                "observationId" to observationId,
                "expectedVersion" to expectedVersion,
                "currentVersion" to current.version,
            )
            throw ApplicationError.PreconditionFailed()
        }
        if (current.observation.status == ObservationStatus.CONFIRMED) {
            log.event(
                Level.DEBUG,
                LogType.BUSINESS,
                "Confirm refused: already confirmed",
                "observationId" to observationId,
                "confirmedBy" to current.confirmedBy,
            )
            throw ApplicationError.Conflict("already-confirmed", "The observation is already confirmed.")
        }
    }
}
