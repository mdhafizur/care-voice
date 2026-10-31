package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.application.TransactionRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException
import javax.sql.DataSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * The connection of the transaction the current coroutine runs in. A coroutine context element (not a ThreadLocal),
 * so it follows the coroutine across suspension points and thread switches.
 */
internal class TransactionConnection(val connection: Connection) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransactionConnection>
}

/**
 * Unit of Work pattern over JDBC: [inTransaction] opens one transaction (READ COMMITTED) and every repository call
 * inside the block joins it via the coroutine context; commit on success, rollback on any exception.
 *
 * Blocking JDBC runs on [Dispatchers.IO]; [MDCContext] copies the caller's MDC (requestId, userId) onto that thread,
 * because the MDC is thread-local and would otherwise be lost in every database log line.
 */
class JdbcTransactions(private val dataSource: DataSource) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        // Nested call: join the outer transaction instead of opening a second connection.
        if (currentCoroutineContext()[TransactionConnection] != null) return block()
        return withContext(Dispatchers.IO + MDCContext()) {
            unavailableAs503 {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val result = withContext(TransactionConnection(connection)) { block() }
                        connection.commit()
                        result
                    } catch (e: Throwable) {
                        runCatching { connection.rollback() }.onFailure { e.addSuppressed(it) }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = true }
                    }
                }
            }
        }
    }

    override suspend fun <T : Any> tryWithAdvisoryLock(lockName: String, block: suspend () -> T): T? = inTransaction {
        if (AdvisoryLocks.tryXactLock(currentConnection(), lockName)) block() else null
    }

    companion object {
        internal suspend fun currentConnection(): Connection =
            currentCoroutineContext()[TransactionConnection]?.connection
                ?: error("No transaction in the current coroutine context")
    }
}

/**
 * Uses the ambient transaction's connection when there is one, otherwise borrows one from [dataSource] for a single
 * auto-commit operation. Always on [Dispatchers.IO] with the caller's MDC.
 */
internal suspend fun <T> withConnection(dataSource: DataSource, block: (Connection) -> T): T {
    val ambient = currentCoroutineContext()[TransactionConnection]?.connection
    return withContext(Dispatchers.IO + MDCContext()) {
        unavailableAs503 { if (ambient != null) block(ambient) else dataSource.connection.use(block) }
    }
}

/** How long a client should wait before retrying while the database is unreachable (`Retry-After`). */
private val DATABASE_RETRY_AFTER = 2.seconds

/**
 * An unreachable database is a 503 with `Retry-After` the client may retry safely (with the same Idempotency-Key),
 * not a 500: the request was fine, the dependency is not (docs/availability.md, "Postgres down"). Any other
 * [SQLException] stays what it is.
 */
private inline fun <T> unavailableAs503(block: () -> T): T = try {
    block()
} catch (e: SQLException) {
    if (!SqlStates.isUnavailable(e)) throw e
    throw ApplicationError.ServiceUnavailable(DATABASE_RETRY_AFTER, "The database is temporarily unavailable.")
        .apply { initCause(e) }
}

/**
 * Transaction-scoped advisory locks only (`pg_try_advisory_xact_lock`): released automatically at commit/rollback,
 * and compatible with PgBouncer in transaction mode, where a session-level lock could leak to another client.
 */
object AdvisoryLocks {
    fun tryXactLock(connection: Connection, lockName: String): Boolean =
        connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, keyOf(lockName))
            statement.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }

    /** A stable 64-bit key from the lock name (first 8 bytes of its SHA-256). */
    fun keyOf(lockName: String): Long =
        ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(lockName.toByteArray(Charsets.UTF_8))).long
}

/** PostgreSQL error classes the adapters react to. */
object SqlStates {
    const val UNIQUE_VIOLATION = "23505"

    /** Walks the cause chain: drivers and pools sometimes wrap the original [SQLException]. */
    fun isUniqueViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is SQLException && it.sqlState == UNIQUE_VIOLATION }

    /**
     * True when the database cannot be reached or is shutting down, as opposed to a problem with the statement:
     * class 08 (connection exception), 57P01-57P03 (admin shutdown, crash shutdown, cannot connect now, e.g. during
     * a failover), and the pool's own "no connection within connectionTimeout" (SQLTransientConnectionException).
     */
    fun isUnavailable(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
        it is SQLTransientConnectionException ||
            it is SQLNonTransientConnectionException ||
            (it is SQLException && it.sqlState?.let { state -> state.startsWith("08") || state in SHUTDOWN } == true)
    }

    private val SHUTDOWN = setOf("57P01", "57P02", "57P03")
}
