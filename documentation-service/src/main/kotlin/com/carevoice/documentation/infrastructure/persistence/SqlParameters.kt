package com.carevoice.documentation.infrastructure.persistence

import java.sql.PreparedStatement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A parameter for [bindAll]; text arrays need the connection, so they are a separate type. */
internal data class TextArray(val values: Collection<String>)

/**
 * Binds positional parameters by type. Instants become `timestamptz` (UTC OffsetDateTime, microsecond precision,
 * which is what PostgreSQL stores), so a value written and read back compares equal.
 */
internal fun PreparedStatement.bindAll(params: List<Any?>) {
    params.forEachIndexed { index, value ->
        val position = index + 1
        when (value) {
            null -> setObject(position, null)
            is String -> setString(position, value)
            is Int -> setInt(position, value)
            is Long -> setLong(position, value)
            is Boolean -> setBoolean(position, value)
            is UUID -> setObject(position, value)
            is Instant -> setObject(position, value.toDbTimestamp())
            is TextArray -> setArray(position, connection.createArrayOf("text", value.values.toTypedArray()))
            else -> setObject(position, value)
        }
    }
}

/** UTC timestamp cut to microseconds, the precision PostgreSQL stores. */
internal fun Instant.toDbTimestamp(): OffsetDateTime = truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)
