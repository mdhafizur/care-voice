package com.carevoice.documentation.infrastructure.persistence

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
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

internal fun Instant.toDbTimestamp(): OffsetDateTime = truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)

internal fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

internal fun ResultSet.instantOrNull(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

internal fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

internal fun <T> Connection.queryList(sql: String, params: List<Any?>, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bindAll(params)
        statement.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

internal fun <T> Connection.queryOne(sql: String, params: List<Any?>, map: (ResultSet) -> T): T? =
    queryList(sql, params, map).firstOrNull()

internal fun Connection.update(sql: String, params: List<Any?>): Int = prepareStatement(sql).use { statement ->
    statement.bindAll(params)
    statement.executeUpdate()
}
