package com.carevoice.alert.api

import com.carevoice.alert.application.AlertServiceException
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
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

private val deadlineLog = LoggerFactory.getLogger("com.carevoice.alert.api.RequestDeadline")

private const val MAX_LOGGED_PATH = 256

/**
 * Upper bound for the processing of one request (`REQUEST_TIMEOUT_MS`). When it is reached, the handler's coroutine is
 * cancelled and the client gets 503 with `Retry-After` (via [AlertServiceException.RequestTimedOut]) instead of waiting
 * until the proxy gives up. Suspending work (MongoDB calls, the guidance LLM) stops at once; the Mongo driver's own
 * socket and write-concern timeouts bound anything below that. Paths in [exemptPaths] are not bounded.
 */
fun Application.installRequestDeadline(timeout: Duration, exemptPaths: Set<String> = emptySet()) {
    intercept(ApplicationCallPipeline.Plugins) {
        // Step 1: paths in exemptPaths get no time limit, so they pass straight through.
        val path = call.request.path()
        if (path in exemptPaths) return@intercept
        // Step 2: run the rest of the request pipeline, and cancel it when the time limit is reached.
        try {
            withTimeout(timeout) { proceed() }
        } catch (e: TimeoutCancellationException) {
            // Step 3: log the method and a shortened path at WARN, then throw, so the client gets 503 with Retry-After.
            deadlineLog.event(
                Level.WARN,
                LogType.REQUEST,
                "Request deadline exceeded",
                "method" to call.request.httpMethod.value,
                "path" to path.take(MAX_LOGGED_PATH),
                "timeoutMs" to timeout.inWholeMilliseconds,
            )
            throw AlertServiceException.RequestTimedOut(timeout)
        }
    }
}
