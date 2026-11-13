package com.carevoice.documentation.application

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/**
 * Keyset pagination cursor (docs/CONTRACTS.md, section 14): base64url of `a|<recordedAt>|<id>` (rows AFTER this key
 * in the descending order, i.e. the next page) or `b|<recordedAt>|<id>` (rows BEFORE it, i.e. the previous page).
 *
 * Why keyset instead of OFFSET: the query seeks the index directly to `(recorded_at, id)`, so page 500 costs the
 * same as page 1, and a row inserted between two fetches neither shifts nor duplicates items.
 */
data class PageCursor(val direction: Direction, val recordedAt: Instant, val id: UUID) {
    enum class Direction(val code: String) {
        AFTER("a"),
        BEFORE("b"),
    }

    /** Turns the cursor into the opaque, URL-safe string that clients send back; [decode] reverses it. */
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "${direction.code}|$recordedAt|$id".toByteArray(Charsets.UTF_8),
    )

    companion object {
        private const val MAX_LENGTH = 200

        /** Returns null for a malformed cursor (→ 400). A well-formed cursor that matches no row yields the first page. */
        fun decode(raw: String): PageCursor? {
            if (raw.isEmpty() || raw.length > MAX_LENGTH) return null
            val text =
                try {
                    String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            val parts = text.split('|')
            if (parts.size != 3) return null
            val direction = Direction.entries.firstOrNull { it.code == parts[0] } ?: return null
            val instant =
                try {
                    Instant.parse(parts[1])
                } catch (_: DateTimeParseException) {
                    return null
                }
            val id =
                try {
                    UUID.fromString(parts[2]).takeIf { it.toString() == parts[2].lowercase() }
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return null
            return PageCursor(direction, instant, id)
        }

        /** Cursor for the page that starts right after [record] (the next page). */
        fun after(record: ObservationRecord): PageCursor = PageCursor(Direction.AFTER, record.recordedAt, record.id)

        /** Cursor for the page that ends right before [record] (the previous page). */
        fun before(record: ObservationRecord): PageCursor = PageCursor(Direction.BEFORE, record.recordedAt, record.id)
    }
}
