package com.carevoice.documentation.infrastructure.persistence

import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException

/**
 * PostgreSQL error codes (SQLState, 5 characters) the adapters react to. The first two characters are the class:
 * 08 = connection problem, 23 = a rule in the table was broken, 25 = transaction state, 57 = the server stopped the
 * work. Full official list: https://www.postgresql.org/docs/current/errcodes-appendix.html
 */
object SqlStates {
    /** `unique_violation`: an insert broke a UNIQUE rule, e.g. the same idempotency key used twice. */
    const val UNIQUE_VIOLATION = "23505"

    /** `query_canceled`: the query ran longer than `statement_timeout` (set in migration V4) and was stopped. */
    const val STATEMENT_TIMEOUT = "57014"

    /** `idle_in_transaction_session_timeout`: a transaction stayed open doing nothing for too long and was ended. */
    const val IDLE_IN_TRANSACTION_TIMEOUT = "25P03"

    /** `admin_shutdown`: Postgres is shutting down on purpose, or an admin ended this session. */
    private const val ADMIN_SHUTDOWN = "57P01"

    /** `crash_shutdown`: Postgres is going down after another server process crashed. */
    private const val CRASH_SHUTDOWN = "57P02"

    /** `cannot_connect_now`: Postgres is still starting up or recovering, e.g. during a failover. */
    private const val CANNOT_CONNECT_NOW = "57P03"

    /** Class 08, `connection_exception`: the connection could not be opened or broke (e.g. 08006). */
    private const val CONNECTION_EXCEPTION_CLASS = "08"

    /** True for any code of class 08, e.g. 08006 `connection_failure` or 08001 "could not connect". */
    private fun isConnectionClass(state: String): Boolean = state.startsWith(CONNECTION_EXCEPTION_CLASS)

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
            (it is SQLException && it.sqlState?.let { code -> isConnectionClass(code) || code in RETRY_LATER } == true)
    }

    /** Codes that become 503 + Retry-After: the server is going away, or the work was stopped for taking too long. */
    private val RETRY_LATER =
        setOf(ADMIN_SHUTDOWN, CRASH_SHUTDOWN, CANNOT_CONNECT_NOW, STATEMENT_TIMEOUT, IDLE_IN_TRANSACTION_TIMEOUT)

    /** Server is shutting down or not accepting connections yet (e.g. during a failover); a read may retry once. */
    private val SERVER_GOING_AWAY = setOf(ADMIN_SHUTDOWN, CRASH_SHUTDOWN, CANNOT_CONNECT_NOW)

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
                it.sqlState?.let { code -> isConnectionClass(code) || code in SERVER_GOING_AWAY } == true
        }
    }

    /** The first SQLState in the cause chain, for logs (a code, never a message). */
    fun firstSqlState(e: Throwable): String? =
        generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstNotNullOfOrNull { it.sqlState }
}
