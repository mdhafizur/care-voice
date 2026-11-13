package com.carevoice.documentation.infrastructure.persistence

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection

/**
 * Transaction-scoped advisory locks only (`pg_try_advisory_xact_lock`): released automatically at commit/rollback,
 * and compatible with PgBouncer in transaction mode, where a session-level lock could leak to another client.
 */
object AdvisoryLocks {
    /** Tries to take the lock for the current transaction without waiting; true when it was taken. */
    fun tryXactLock(connection: Connection, lockName: String): Boolean =
        connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, keyOf(lockName))
            statement.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }

    /** A stable 64-bit key from the lock name (first 8 bytes of its SHA-256). */
    fun keyOf(lockName: String): Long =
        ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(lockName.toByteArray(Charsets.UTF_8))).long
}
