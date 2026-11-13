package com.carevoice.documentation.infrastructure.persistence

import java.sql.Connection
import java.sql.ResultSet

/** Runs a query with bound parameters and maps every row with [map]. */
internal fun <T> Connection.queryList(sql: String, params: List<Any?>, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bindAll(params)
        statement.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

/** Like [queryList], but returns only the first row, or null when there is none. */
internal fun <T> Connection.queryOne(sql: String, params: List<Any?>, map: (ResultSet) -> T): T? =
    queryList(sql, params, map).firstOrNull()

/** Runs an INSERT, UPDATE or DELETE with bound parameters and returns the number of changed rows. */
internal fun Connection.update(sql: String, params: List<Any?>): Int = prepareStatement(sql).use { statement ->
    statement.bindAll(params)
    statement.executeUpdate()
}
