package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.TransactionRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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
        if (currentTransactionConnection() != null) return block()
        // Step 2: otherwise run on the IO threads with the log context (MDC); a lost database becomes a 503.
        return onDatabaseThread {
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
            currentTransactionConnection() ?: error("No transaction in the current coroutine context")
    }
}
