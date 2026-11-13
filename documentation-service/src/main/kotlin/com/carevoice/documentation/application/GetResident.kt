package com.carevoice.documentation.application

import org.slf4j.LoggerFactory

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
        return residents.requireVisibleResident(caller, residentId, log)
    }
}
