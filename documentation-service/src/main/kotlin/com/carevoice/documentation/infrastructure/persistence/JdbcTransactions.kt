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
        // Step 1: already inside a transaction? Then join it instead of opening a second connection.
        if (currentTransactionConnection() != null) return block()
        // Step 2: otherwise borrow a connection on the database threads and run the block in a new transaction.
        return onDatabaseThread {
            dataSource.connection.use { connection -> runInNewTransaction(connection, block) }
        }
    }

    /**
     * One transaction on [connection]: the block runs with the connection in its coroutine context, so every
     * repository it calls uses this same connection. Commit on success; any error rolls back and is rethrown.
     */
    private suspend fun <T> runInNewTransaction(connection: Connection, block: suspend () -> T): T {
        // Step 1: turn off auto-commit, so every statement in the block is part of one transaction.
        connection.autoCommit = false
        try {
            // Step 2: run the block with this connection in the coroutine context; commit on success.
            val result = withContext(TransactionConnection(connection)) { block() }
            connection.commit()
            return result
        } catch (e: Throwable) {
            // Step 3: on any error, roll back and rethrow; a failed rollback is added to that error.
            runCatching { connection.rollback() }.onFailure { e.addSuppressed(it) }
            throw e
        } finally {
            // Step 4: give the connection back to the pool in its normal auto-commit mode.
            runCatching { connection.autoCommit = true }
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
