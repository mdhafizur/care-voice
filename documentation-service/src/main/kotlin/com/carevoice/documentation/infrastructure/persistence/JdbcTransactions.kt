package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.application.TransactionRunner
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException
import javax.sql.DataSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
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
    /**
     * Runs [block] in one JDBC transaction on [Dispatchers.IO]: commit on success, rollback on any exception.
     * A nested call joins the outer transaction. An unreachable database becomes a 503.
     */
    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        // Step 1: are we already inside a transaction?
        // Nested call: join the outer transaction instead of opening a second connection.
        if (currentCoroutineContext()[TransactionConnection] != null) return block()
        // Step 2: otherwise run on the IO threads with the log context (MDC); a lost database becomes a 503.
        return withContext(Dispatchers.IO + MDCContext()) {
            unavailableAs503 {
                dataSource.connection.use { connection ->
                    // Step 3: turn off auto-commit, so every statement in the block is part of one transaction.
                    connection.autoCommit = false
                    try {
                        // Step 4: run the block with this connection in the coroutine context; commit on success.
                        val result = withContext(TransactionConnection(connection)) { block() }
                        connection.commit()
                        result
                    } catch (e: Throwable) {
                        // Step 5: on any error, roll back and rethrow; a failed rollback is added to that error.
                        runCatching { connection.rollback() }.onFailure { e.addSuppressed(it) }
                        throw e
                    } finally {
                        // Step 6: give the connection back to the pool in its normal auto-commit mode.
                        runCatching { connection.autoCommit = true }
                    }
                }
            }
        }
    }

    /**
     * Runs [block] only when this transaction gets the advisory lock (a named lock the application defines in
     * PostgreSQL); returns null at once when another transaction holds it.
     */
    override suspend fun <T : Any> tryWithAdvisoryLock(lockName: String, block: suspend () -> T): T? = inTransaction {
        if (AdvisoryLocks.tryXactLock(currentConnection(), lockName)) block() else null
    }

    companion object {
        /** The connection of the current coroutine's transaction; throws when the caller is not in a transaction. */
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

private val readRetryLog = LoggerFactory.getLogger("com.carevoice.documentation.infrastructure.persistence.ReadRetry")

/**
 * [withConnection] for a read. Outside a transaction, a read that fails because its connection was lost or refused
 * (a Postgres failover, a restarted PgBouncer, a connection the pool still held from before) is retried ONCE on a
 * fresh connection after a short jittered pause (100 to 300 ms); the pool has already evicted the broken connection.
 * The nurse gets the data instead of a 503 for a blip that lasted less than a second.
 *
 * Deliberately not retried:
 * - writes: the commit may have happened before the connection broke, so a retry could apply it twice. Clients retry
 *   writes themselves, safely, with their Idempotency-Key.
 * - anything inside [JdbcTransactions.inTransaction]: the transaction is gone, only the caller can repeat all of it.
 * - a statement timeout (57014): the query is slow, and running it again doubles the load on a struggling database.
 * - "no connection from the pool in time": the database is down, not blipping; waiting again only adds latency.
 */
internal suspend fun <T> withReadConnection(dataSource: DataSource, block: (Connection) -> T): T {
    // Step 1: inside a transaction, use its connection and never retry (see above).
    if (currentCoroutineContext()[TransactionConnection] != null) return withConnection(dataSource, block)
    // Step 2: outside one, read on the IO threads; a database that cannot be reached becomes a 503.
    return withContext(Dispatchers.IO + MDCContext()) {
        unavailableAs503 {
            try {
                // Step 3: try the read on a connection from the pool.
                dataSource.connection.use(block)
            } catch (e: SQLException) {
                // Step 4: retry only a lost connection: log it, wait a short random pause, then read once more
                // on a new connection.
                if (!SqlStates.isConnectionLost(e)) throw e
                readRetryLog.event(
                    Level.WARN,
                    LogType.DB_OPERATION,
                    "Read failed on a lost connection; retrying once",
                    "sqlState" to SqlStates.firstSqlState(e),
                    "error" to e.javaClass.simpleName,
                )
                delay(Random.nextLong(READ_RETRY_MIN_MS, READ_RETRY_MAX_MS).milliseconds)
                dataSource.connection.use(block)
            }
        }
    }
}

private const val READ_RETRY_MIN_MS = 100L
private const val READ_RETRY_MAX_MS = 300L

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

/** PostgreSQL error classes the adapters react to. */
object SqlStates {
    const val UNIQUE_VIOLATION = "23505"
    const val STATEMENT_TIMEOUT = "57014"
    const val IDLE_IN_TRANSACTION_TIMEOUT = "25P03"

    /** Walks the cause chain: drivers and pools sometimes wrap the original [SQLException]. */
    fun isUniqueViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is SQLException && it.sqlState == UNIQUE_VIOLATION }

    /**
     * True when the database cannot be reached, is shutting down or is too slow right now, as opposed to a problem
     * with the statement: class 08 (connection exception), 57P01-57P03 (admin shutdown, crash shutdown, cannot connect
     * now, e.g. during a failover), 57014 (statement cancelled by statement_timeout, migration V4), 25P03 (session
     * ended by idle_in_transaction_session_timeout), and the pool's own "no connection within connectionTimeout"
     * (SQLTransientConnectionException). All of them are worth a retry later, so they become 503 + Retry-After.
     */
    fun isUnavailable(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
        it is SQLTransientConnectionException ||
            it is SQLNonTransientConnectionException ||
            (it is SQLException && it.sqlState?.let { state -> state.startsWith("08") || state in RETRY_LATER } == true)
    }

    private val RETRY_LATER = setOf("57P01", "57P02", "57P03", STATEMENT_TIMEOUT, IDLE_IN_TRANSACTION_TIMEOUT)

    /** Server is shutting down or not accepting connections yet (e.g. during a failover). */
    private val SERVER_GOING_AWAY = setOf("57P01", "57P02", "57P03")

    /**
     * True when an established or new connection broke (class 08, or the server going away: 57P01-57P03), which a read
     * may retry once on a fresh connection. False when the pool could not hand out a connection in time
     * ([SQLTransientConnectionException]): then the database is down, not blipping.
     */
    fun isConnectionLost(e: Throwable): Boolean {
        val chain = generateSequence(e) { it.cause }.toList()
        if (chain.any { it is SQLTransientConnectionException }) return false
        return chain.any {
            it is SQLException &&
                it.sqlState?.let { state -> state.startsWith("08") || state in SERVER_GOING_AWAY } == true
        }
    }

    /** The first SQLState in the cause chain, for logs (a code, never a message). */
    fun firstSqlState(e: Throwable): String? =
        generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstNotNullOfOrNull { it.sqlState }
}
