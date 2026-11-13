package com.carevoice.alert.application

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * Opaque keyset cursor (docs/CONTRACTS.md, sections 6 and 14): base64url (no padding) of `a|<createdAt>|<id>` for
 * "after this alert" (next page, older items) or `b|<createdAt>|<id>` for "before this alert" (previous page, newer
 * items).
 *
 * Why keyset instead of offset: an alert inserted while a ward lead pages through the list neither shifts items onto
 * the next page (duplicates) nor pushes one off (gaps), and the query stays an index range scan at any depth.
 */
object CursorCodec {
    private const val AFTER = "a"
    private const val BEFORE = "b"
    private const val MAX_ENCODED_LENGTH = 512

    /** Turns [keyset] into the opaque cursor string; [decode] reverses it. */
    fun encode(keyset: Keyset): String {
        val prefix = if (keyset.direction == Keyset.Direction.OLDER) AFTER else BEFORE
        val raw = "$prefix|${keyset.createdAt}|${keyset.id}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    /** @throws AlertServiceException.InvalidCursor when [cursor] is not a cursor this codec produced. */
    fun decode(cursor: String): Keyset {
        if (cursor.isEmpty() || cursor.length > MAX_ENCODED_LENGTH) {
            throw AlertServiceException.InvalidCursor("unexpected length")
        }
        val raw =
            try {
                String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                throw AlertServiceException.InvalidCursor("not base64url")
            }
        val parts = raw.split('|', limit = 3)
        if (parts.size != 3) throw AlertServiceException.InvalidCursor("unexpected structure")
        val direction =
            when (parts[0]) {
                AFTER -> Keyset.Direction.OLDER
                BEFORE -> Keyset.Direction.NEWER
                else -> throw AlertServiceException.InvalidCursor("unknown direction")
            }
        val createdAt =
            try {
                Instant.parse(parts[1])
            } catch (_: DateTimeParseException) {
                throw AlertServiceException.InvalidCursor("invalid sort key")
            }
        val id = parts[2]
        if (id.isBlank() || id.length > ProcessObservationEvent.MAX_ID_LENGTH) {
            throw AlertServiceException.InvalidCursor("invalid id")
        }
        return Keyset(createdAt, id, direction)
    }
}
