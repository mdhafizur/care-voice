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

    /** Returns one page of the resident's observations; 404 when the resident is not in the caller's ward. */
    suspend fun execute(caller: Caller, residentId: String, page: PageRequest): Page<ObservationRecord> {
        caller.requireAnyRole()
        residents.requireVisibleResident(caller, residentId, log)
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

    /** Emits every observation of the resident, fetching [pageSize] rows per query (see the class comment). */
    fun asFlow(caller: Caller, residentId: String, pageSize: Int = PageLimits.MAX): Flow<ObservationRecord> = flow {
        caller.requireAnyRole()
        residents.requireVisibleResident(caller, residentId, log)
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
}
