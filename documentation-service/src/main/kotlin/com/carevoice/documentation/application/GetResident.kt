package com.carevoice.documentation.application

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Use case: read one resident of the caller's ward.
 *
 * The [ResidentRepository] it receives is the caching decorator in production (cache-aside, Caffeine L1), which is
 * invisible here: the use case depends on the port only.
 */
class GetResident(private val residents: ResidentRepository) {
    private val log = LoggerFactory.getLogger(GetResident::class.java)

    /** Returns the resident, or a 404 when it does not exist or belongs to another ward. */
    suspend fun execute(caller: Caller, residentId: String): Resident {
        caller.requireAnyRole()
        val resident = residents.findById(caller.tenantId, residentId)
        if (resident == null || !caller.canSee(resident.tenantId, resident.wardId)) {
            // The response is the same 404 either way (no cross-ward enumeration); only the log says which case.
            log.event(
                Level.DEBUG,
                LogType.BUSINESS,
                "Resident not visible",
                "residentId" to residentId.take(MAX_LOGGED_ID),
                "reason" to if (resident == null) "not-found" else "other-ward",
            )
            throw ApplicationError.NotFound("Resident", residentId)
        }
        return resident
    }
}

/** Ids from the URL are user input: capped before they reach a log line. */
internal const val MAX_LOGGED_ID = 256
