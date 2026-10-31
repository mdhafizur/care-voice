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

    /** Server error codes for "not primary" / "node is recovering" / "shutdown in progress" / "interrupted". */
    private val TRANSIENT_CODES =
        setOf(6, 7, 89, 91, 189, 262, 9001, 10107, 11600, 11602, 13388, 13435, 13436)

    suspend fun <T> translate(block: suspend () -> T): T = try {
        block()
    } catch (e: MongoException) {
        throw if (isTransient(e)) AlertServiceException.DependencyUnavailable(DEPENDENCY, e) else e
    }

    fun isTransient(e: Throwable): Boolean = when (e) {
        is MongoTimeoutException,
        is MongoSocketException,
        is MongoNotPrimaryException,
        is MongoNodeIsRecoveringException,
        is MongoConnectionPoolClearedException,
        is MongoExecutionTimeoutException,
        is MongoWriteConcernException,
        -> true

        is MongoException ->
            e.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) ||
                e.hasErrorLabel("RetryableWriteError") ||
                e.code in TRANSIENT_CODES

        else -> false
    }

    fun isDuplicateKey(e: MongoWriteException): Boolean = e.error.category == ErrorCategory.DUPLICATE_KEY

    /** True when every per-document error of a bulk insert is a duplicate key and no write concern error occurred. */
    fun onlyDuplicateKeys(e: MongoBulkWriteException): Boolean = e.writeConcernError == null &&
        e.writeErrors.isNotEmpty() &&
        e.writeErrors.all { ErrorCategory.fromErrorCode(it.code) == ErrorCategory.DUPLICATE_KEY }
}
