package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
internal val DATABASE_RETRY_AFTER = 2.seconds

/**
 * An unreachable database is a 503 with `Retry-After` the client may retry safely (with the same Idempotency-Key),
 * not a 500: the request was fine, the dependency is not (docs/availability.md, "Postgres down"). Any other
 * [SQLException] stays what it is.
 */
internal inline fun <T> unavailableAs503(block: () -> T): T = try {
    block()
} catch (e: SQLException) {
    if (!SqlStates.isUnavailable(e)) throw e
    throw ApplicationError.ServiceUnavailable(DATABASE_RETRY_AFTER, "The database is temporarily unavailable.")
        .apply { initCause(e) }
}
