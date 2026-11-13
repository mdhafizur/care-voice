package com.carevoice.alert.infrastructure.persistence

import com.carevoice.alert.application.AlertServiceException
import com.mongodb.ErrorCategory
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoConnectionPoolClearedException
import com.mongodb.MongoException
import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.MongoNodeIsRecoveringException
import com.mongodb.MongoNotPrimaryException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import com.mongodb.MongoWriteConcernException
import com.mongodb.MongoWriteException

/**
 * Adapter boundary for driver failures: translates the MongoDB exceptions that mean "temporarily unavailable" (no
 * primary during a replica-set failover, server-selection timeout, socket errors, a cleared pool, a majority write
 * concern that timed out) into [AlertServiceException.DependencyUnavailable].
 *
 * Why here and not in the API's StatusPages: only `infrastructure` may know the driver, so the HTTP layer maps one
 * application-level error to 503 + Retry-After and the AMQP consumer maps the same error to "retry later" — neither
 * needs to know which driver or database is behind the port.
 */
internal object MongoErrors {
    private const val DEPENDENCY = "MongoDB"

    /**
     * MongoDB server error codes that mean "try again later": a network problem, a server shutting down, or a
     * replica-set failover (the primary, the server that takes writes, changed). Each code with its official name.
     * Full official list: https://github.com/mongodb/mongo/blob/master/src/mongo/base/error_codes.yml
     */
    private val TRANSIENT_CODES =
        setOf(
            // HostUnreachable: the server could not be reached
            6,
            // HostNotFound: the server's name could not be resolved
            7,
            // NetworkTimeout: the network did not answer in time
            89,
            // ShutdownInProgress: the server is shutting down
            91,
            // PrimarySteppedDown: the primary gave up its role during a failover
            189,
            // ExceededTimeLimit: an internal operation ran out of time
            262,
            // SocketException: the network connection broke
            9001,
            // NotWritablePrimary: this server is no longer the primary, so it takes no writes
            10107,
            // InterruptedAtShutdown: the operation was stopped because the server is shutting down
            11600,
            // InterruptedDueToReplStateChange: stopped because the replica set changed roles
            11602,
            // StaleConfig: the server's copy of the cluster setup is out of date
            13388,
            // NotPrimaryNoSecondaryOk: not the primary, and reads from a secondary were not allowed
            13435,
            // NotPrimaryOrSecondary: the server is neither primary nor secondary right now (e.g. starting up)
            13436,
        )

    /**
     * Runs [block] and turns transient MongoDB errors into [AlertServiceException.DependencyUnavailable].
     * Other errors are rethrown unchanged.
     */
    suspend fun <T> translate(block: suspend () -> T): T = try {
        block()
    } catch (e: MongoException) {
        throw if (isTransient(e)) AlertServiceException.DependencyUnavailable(DEPENDENCY, e) else e
    }

    /**
     * True for errors that may go away by themselves: timeouts, network errors, a failover,
     * a retryable error label, or a server code in [TRANSIENT_CODES].
     */
    fun isTransient(e: Throwable): Boolean = when (e) {
        is MongoTimeoutException,
        is MongoSocketException,
        is MongoNotPrimaryException,
        is MongoNodeIsRecoveringException,
        is MongoConnectionPoolClearedException,
        is MongoExecutionTimeoutException,
        is MongoWriteConcernException,
        -> true

        // Error labels are tags the server puts on an error. `RetryableWriteError` means the write may be sent again.
        is MongoException ->
            e.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) ||
                e.hasErrorLabel("RetryableWriteError") ||
                e.code in TRANSIENT_CODES

        else -> false
    }

    /** True when a single-document write failed because the unique key already exists. */
    fun isDuplicateKey(e: MongoWriteException): Boolean = e.error.category == ErrorCategory.DUPLICATE_KEY

    /** True when every per-document error of a bulk insert is a duplicate key and no write concern error occurred. */
    fun onlyDuplicateKeys(e: MongoBulkWriteException): Boolean = e.writeConcernError == null &&
        e.writeErrors.isNotEmpty() &&
        e.writeErrors.all { ErrorCategory.fromErrorCode(it.code) == ErrorCategory.DUPLICATE_KEY }
}
