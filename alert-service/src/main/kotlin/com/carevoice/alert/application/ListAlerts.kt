package com.carevoice.alert.application

import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.AlertStatus
import com.carevoice.alert.domain.WardScope
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * One page of alerts.
 *
 * @property nextCursor cursor of the following (older) page, null on the last page.
 * @property prevCursor cursor of the preceding (newer) page, null on the first page.
 * @property totalCount alerts matching the filter across all pages (`X-Total-Count`).
 */
data class AlertPage(val items: List<Alert>, val nextCursor: String?, val prevCursor: String?, val totalCount: Long)

/**
 * Use case: list the alerts of the caller's ward, newest first, with keyset pagination (docs/CONTRACTS.md, section 14).
 *
 * Each query fetches `limit + 1` rows; the extra row only signals that another page exists. The previous page is read
 * with the mirrored ascending query and reversed in memory, so both directions use the same compound index.
 */
class ListAlerts(private val alerts: AlertRepository) {
    private val log = LoggerFactory.getLogger(ListAlerts::class.java)

    /**
     * Returns one page of the ward's alerts, newest first, optionally only those with [status].
     * A malformed cursor throws InvalidCursor (400). A well-formed cursor whose alert is not found in the ward,
     * or whose time does not match, starts again at the first page.
     */
    suspend fun execute(scope: WardScope, status: AlertStatus?, limit: Int, cursor: String?): AlertPage {
        // Step 1: the page size must be in the allowed range.
        require(limit in MIN_LIMIT..MAX_LIMIT) { "limit must be in $MIN_LIMIT..$MAX_LIMIT" }
        // Step 2: read the cursor and check that it still points to an alert of this ward.
        // Decode first: a malformed cursor is a 400 even when the ward has no alerts.
        var keyset = cursor?.let { CursorCodec.decode(it) }
        // Well-formed but unknown (e.g. the alert belongs to another ward or never existed) → first page.
        val cursorSent = keyset != null
        if (keyset != null && alerts.findById(scope, keyset.id)?.createdAt != keyset.createdAt) keyset = null

        // Step 3: count all matching alerts, then fetch the page in the direction the cursor asks for
        // (no cursor means the first page).
        val total = alerts.count(scope, status)
        val page =
            when (keyset?.direction) {
                null -> firstPage(scope, status, limit, total)
                Keyset.Direction.OLDER -> olderPage(scope, status, keyset, limit, total)
                Keyset.Direction.NEWER -> newerPage(scope, status, keyset, limit, total)
            }
        // Step 4: one DEBUG log line with the filter and the result size, then return the page.
        log.event(
            Level.DEBUG,
            LogType.DB_OPERATION,
            "Alerts listed",
            "wardId" to scope.wardId,
            "status" to status?.name,
            "limit" to limit,
            "cursor" to cursorSent,
            // A well-formed cursor of an alert that is gone (or of another ward) restarts at the first page.
            "cursorReset" to (cursorSent && keyset == null),
            "direction" to keyset?.direction?.name,
            "returned" to page.items.size,
            "totalCount" to total,
        )
        return page
    }

    /** The newest [limit] alerts; the first page never has a previous-page cursor. */
    private suspend fun firstPage(scope: WardScope, status: AlertStatus?, limit: Int, total: Long): AlertPage {
        val rows = alerts.findPage(scope, status, null, limit + 1)
        val items = rows.take(limit)
        return AlertPage(items, nextCursorOf(items, rows.size > limit), prevCursor = null, totalCount = total)
    }

    /** The page after the cursor (older alerts). It has a previous-page cursor unless it is empty. */
    private suspend fun olderPage(
        scope: WardScope,
        status: AlertStatus?,
        keyset: Keyset,
        limit: Int,
        total: Long,
    ): AlertPage {
        val rows = alerts.findPage(scope, status, keyset, limit + 1)
        val items = rows.take(limit)
        // We arrived here by moving past at least one newer alert (the cursor's), so a previous page exists.
        val prev = items.firstOrNull()?.let { CursorCodec.encode(Keyset(it.createdAt, it.id, Keyset.Direction.NEWER)) }
        return AlertPage(items, nextCursorOf(items, rows.size > limit), prev, total)
    }

    /**
     * The page before the cursor (newer alerts). The rows come back oldest first and are reversed here.
     * When fewer than a full page are newer than the cursor, this is the top of the list,
     * so the real first page is returned instead.
     */
    private suspend fun newerPage(
        scope: WardScope,
        status: AlertStatus?,
        keyset: Keyset,
        limit: Int,
        total: Long,
    ): AlertPage {
        val ascending = alerts.findPage(scope, status, keyset, limit + 1)
        if (ascending.isEmpty()) return firstPage(scope, status, limit, total)
        val hasNewer = ascending.size > limit
        val items = ascending.take(limit).reversed()
        if (!hasNewer && items.size < limit) {
            // Fewer than a full page newer than the cursor: this is the top of the list, serve the real first page.
            return firstPage(scope, status, limit, total)
        }
        val first = items.first()
        val prev = if (hasNewer) CursorCodec.encode(Keyset(first.createdAt, first.id, Keyset.Direction.NEWER)) else null
        // The cursor's own alert is older than every item here, so a next page always exists.
        return AlertPage(items, nextCursorOf(items, hasMore = true), prev, total)
    }

    /** Cursor that points after the last item, or null when there are no more items. */
    private fun nextCursorOf(items: List<Alert>, hasMore: Boolean): String? {
        val last = items.lastOrNull() ?: return null
        return if (hasMore) CursorCodec.encode(Keyset(last.createdAt, last.id, Keyset.Direction.OLDER)) else null
    }

    companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 100
        const val DEFAULT_LIMIT = 20
    }
}
