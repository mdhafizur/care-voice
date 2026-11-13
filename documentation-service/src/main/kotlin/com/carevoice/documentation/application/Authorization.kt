package com.carevoice.documentation.application

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import org.slf4j.Logger
import org.slf4j.event.Level

/**
 * Role check shared by every use case (docs/CONTRACTS.md, section 5). The HTTP layer checks roles too (so SSE can
 * answer 403 before the stream starts); checking again here keeps the rule true for every caller of the use case,
 * not just for the routes that remembered to.
 */
internal fun Caller.requireAnyRole(allowed: Set<Role> = Role.CARE_STAFF) {
    if (!hasAnyRole(allowed)) throw ApplicationError.Forbidden()
}

/** Tenant/ward isolation: a resource of another ward is "not found", never "forbidden" (do not reveal it exists). */
internal fun Caller.canSee(tenantId: String, wardId: String): Boolean =
    this.tenantId == tenantId && this.wardId == wardId

/**
 * Returns the resident [residentId] when it is in the caller's ward; otherwise throws a 404.
 *
 * A malformed id, an unknown id and a resident of another ward all give the same 404, so a client cannot find out
 * which residents exist in other wards. Only the DEBUG line says which case it was. It is written to [log], the
 * logger of the calling use case, so the line keeps that use case's logger name.
 */
internal suspend fun ResidentRepository.requireVisibleResident(
    caller: Caller,
    residentId: String,
    log: Logger,
): Resident {
    // Step 1: a malformed id cannot exist: same 404, but without a database call.
    if (!isWellFormedResidentId(residentId)) {
        log.event(Level.DEBUG, LogType.BUSINESS, "Resident not visible", "reason" to "malformed-id")
        throw ApplicationError.NotFound("Resident", residentId)
    }
    // Step 2: load the resident; an unknown one and one from another ward both give the same 404.
    val resident = findById(caller.tenantId, residentId)
    if (resident == null || !caller.canSee(resident.tenantId, resident.wardId)) {
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
