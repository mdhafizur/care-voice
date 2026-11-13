package com.carevoice.documentation.application

import com.carevoice.domain.model.ResidentId
import java.util.UUID

/*
 * Checks for ids that arrive as user input (URL path, cursor, `Last-Event-ID`), shared by several use cases.
 */

/** A path id that is not a UUID cannot name an existing resource: that is a 404, not a 400. */
internal fun parseUuid(raw: String): UUID? = try {
    UUID.fromString(raw).takeIf { it.toString() == raw.lowercase() }
} catch (_: IllegalArgumentException) {
    null
}

/** Ids from the URL are user input: capped before they reach a log line. */
internal const val MAX_LOGGED_ID = 256

/** True when [raw] follows the domain's [ResidentId] rules (1 to 64 letters, digits or '-'). */
internal fun isWellFormedResidentId(raw: String): Boolean = ResidentId.of(raw).isRight()
