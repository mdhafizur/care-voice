package com.carevoice.documentation.application

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Use case: a resident's observation history, newest first, keyset-paginated.
 *
 * [execute] serves one HTTP page. [asFlow] is a COLD flow over the whole history: nothing is queried until it is
 * collected, each page is fetched only when the collector has consumed the previous one (natural backpressure), and
 * collecting it twice runs the queries twice. Useful for exports and tests that walk every page.
 */
class ListResidentObservations(
    private val residents: ResidentRepository,
    private val observations: ObservationRepository,
) {
    private val log = LoggerFactory.getLogger(ListResidentObservations::class.java)

    suspend fun execute(caller: Caller, residentId: String, page: PageRequest): Page<ObservationRecord> {
        caller.requireAnyRole()
        requireVisibleResident(caller, residentId)
        return observations.listByResident(caller.tenantId, caller.wardId, residentId, page).also {
            log.event(
                Level.DEBUG,
                LogType.DB_OPERATION,
                "Resident observations listed",
                "residentId" to residentId,
                "limit" to page.limit,
                "cursor" to (page.cursor != null),
                "returned" to it.items.size,
                "hasNext" to (it.nextCursor != null),
            )
        }
    }

    fun asFlow(caller: Caller, residentId: String, pageSize: Int = PageLimits.MAX): Flow<ObservationRecord> = flow {
        caller.requireAnyRole()
        requireVisibleResident(caller, residentId)
        var cursor: PageCursor? = null
        do {
            val page = observations.listByResident(
                caller.tenantId,
                caller.wardId,
                residentId,
                PageRequest(pageSize, cursor),
            )
            page.items.forEach { emit(it) }
            cursor = page.nextCursor?.let { PageCursor.decode(it) }
        } while (cursor != null)
    }

    private suspend fun requireVisibleResident(caller: Caller, residentId: String) {
        val resident = residents.findById(caller.tenantId, residentId)
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
    }
}
