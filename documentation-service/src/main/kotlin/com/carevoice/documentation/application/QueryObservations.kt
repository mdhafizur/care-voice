package com.carevoice.documentation.application

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Use case: ward-wide observation search (`QUERY /v1/observations`, `POST /v1/observations/search`).
 *
 * Reads go to [readObservations], which is backed by the read replica when one is configured (and falls back to the
 * primary when the replica is down). This is the ONLY use case allowed to read from the replica: a few hundred
 * milliseconds of replication lag are acceptable for a search list, not for read-your-writes flows such as the
 * idempotent replay or the confirm-then-read-back.
 *
 * Results are cached for 10 s per ward + filter + page (cache-aside, [ObservationQueryCache]) and invalidated when a
 * new observation is committed in that ward, on every replica, via the Redis invalidation bus.
 */
class QueryObservations(
    private val readObservations: ObservationRepository,
    private val cache: ObservationQueryCache,
) {
    private val log = LoggerFactory.getLogger(QueryObservations::class.java)

    /**
     * Returns one page of the ward's matching observations, from the cache when possible.
     * `recordedFrom` after `recordedTo` is a 422 error.
     */
    suspend fun execute(caller: Caller, filter: ObservationFilter, page: PageRequest): Page<ObservationRecord> {
        // Step 1: only logged-in staff may query observations.
        caller.requireAnyRole()
        // Step 2: a time range that starts after it ends is a 422 (well formed, but it makes no sense).
        val from = filter.recordedFrom
        val to = filter.recordedTo
        if (from != null && to != null && from.isAfter(to)) {
            throw ApplicationError.Unprocessable(
                "The request is semantically invalid.",
                listOf(FieldError("recordedFrom", "must not be after recordedTo")),
            )
        }
        // Step 3: read through the cache. The key holds tenant and ward, so one ward never gets another ward's
        // cached result.
        val key = ObservationQueryKey(caller.tenantId, caller.wardId, filter, page)
        return cache.getOrLoad(key) { readObservations.query(caller.tenantId, caller.wardId, filter, page) }.also {
            if (log.isDebugEnabled) {
                // Which filters were used (names only), not their values.
                val used =
                    listOfNotNull(
                        "residentIds".takeIf { filter.residentIds.isNotEmpty() },
                        "vitalTypes".takeIf { filter.vitalTypes.isNotEmpty() },
                        "status".takeIf { filter.status != null },
                        "recordedFrom".takeIf { from != null },
                        "recordedTo".takeIf { to != null },
                    )
                log.event(
                    Level.DEBUG,
                    LogType.DB_OPERATION,
                    "Observations queried",
                    "filters" to used.joinToString(",").ifEmpty { "none" },
                    "limit" to page.limit,
                    "cursor" to (page.cursor != null),
                    "returned" to it.items.size,
                    "totalCount" to it.totalCount,
                )
            }
        }
    }
}
