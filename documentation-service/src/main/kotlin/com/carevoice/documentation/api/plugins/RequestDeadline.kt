package com.carevoice.documentation.api.plugins

import com.carevoice.documentation.api.loggedPath
import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val deadlineLog = LoggerFactory.getLogger("com.carevoice.documentation.api.RequestDeadline")

/** How long a client should wait before retrying a request that ran out of time. */
private val DEADLINE_RETRY_AFTER = 2.seconds

/**
 * Upper bound for the processing of one request (`REQUEST_TIMEOUT_MS`). When it is reached, the handler's coroutine is
 * cancelled and the client gets 503 with `Retry-After` instead of waiting until the proxy gives up.
 *
 * Cancellation is cooperative: suspending work (HTTP calls to the AI provider, Redis, the alert-service) stops at once;
 * a blocking JDBC statement finishes first, which Postgres' `statement_timeout` bounds (docs/CONTRACTS.md, section 6,
 * "Time limits"). Transactions that are cut off roll back, so a timed-out write never half-commits. Long-lived streams
 * in [exemptPaths] (SSE) are not bounded.
 */
fun Application.installRequestDeadline(timeout: Duration, exemptPaths: Set<String>) {
    intercept(ApplicationCallPipeline.Plugins) {
        // Step 1: the exempt paths get no deadline.
        val path = call.request.path()
        if (path in exemptPaths) return@intercept
        // Step 2: run the rest of the request with a time limit.
        try {
            withTimeout(timeout) { proceed() }
        } catch (e: TimeoutCancellationException) {
            // Step 3: when time runs out, log it and answer 503 (service unavailable), so the client
            // retries later.
            deadlineLog.event(
                Level.WARN,
                LogType.REQUEST,
                "Request deadline exceeded",
                "method" to call.request.httpMethod.value,
                "path" to call.loggedPath(),
                "timeoutMs" to timeout.inWholeMilliseconds,
            )
            throw ApplicationError.ServiceUnavailable(
                DEADLINE_RETRY_AFTER,
                "The request did not finish in time. Retry later.",
            )
        }
    }
}
