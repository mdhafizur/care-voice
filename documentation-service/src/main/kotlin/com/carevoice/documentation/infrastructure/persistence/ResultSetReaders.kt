package com.carevoice.documentation.infrastructure.persistence

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Reads a non-null `timestamptz` column as an [Instant]. */
internal fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

/** Reads a nullable `timestamptz` column; SQL NULL gives null. */
internal fun ResultSet.instantOrNull(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

/** Reads a `uuid` column. */
internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

/** Reads an integer column; SQL NULL gives null instead of JDBC's 0. */
internal fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
