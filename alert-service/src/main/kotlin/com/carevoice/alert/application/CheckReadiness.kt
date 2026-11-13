package com.carevoice.alert.application

import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * Readiness use case: every check must pass, and a pod that is shutting down reports not ready FIRST so the load
 * balancer stops routing to it before in-flight work is drained (docs/CONTRACTS.md, section 9b).
 */
class CheckReadiness(private val checks: List<DependencyHealthCheck>) {
    @Volatile
    private var shuttingDown = false

    /** Last state per check, so a change is logged once instead of on every 5-second probe. */
    private val lastState = ConcurrentHashMap<String, HealthState>()

    /** From now on every check reports not ready, so the load balancer stops sending traffic before the drain. */
    fun markShuttingDown() {
        shuttingDown = true
    }

    /**
     * Runs every check; ready only when all are UP and the pod is not shutting down.
     * A check that throws counts as DOWN. Cancellation is passed on.
     */
    suspend fun check(): ReadinessReport {
        // Step 1: run every dependency check (each one is timed and logged). A check that throws counts as DOWN.
        val results =
            checks.associate { check ->
                val state = runCheck(check)
                check.name to state
            }
        // Step 2: build the report. Ready means: not shutting down and every check is UP.
        if (shuttingDown) log.event(Level.DEBUG, LogType.BUSINESS, "Readiness DOWN: shutting down")
        return ReadinessReport(
            ready = !shuttingDown && results.values.all { it == HealthState.UP },
            checks = results,
        )
    }

    /**
     * Runs one check, times it and logs the result. A check that throws counts as DOWN; cancellation is passed on.
     */
    private suspend fun runCheck(check: DependencyHealthCheck): HealthState {
        // Step 1: run the check and time it. An exception means DOWN; it is kept for the log.
        val mark = TimeSource.Monotonic.markNow()
        val (healthy, cause) =
            try {
                check.isHealthy() to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false to e
            }
        // Step 2: turn the result into UP or DOWN and log it.
        val state = if (healthy) HealthState.UP else HealthState.DOWN
        // /readyz runs every few seconds per pod: a healthy, fast check is TRACE (hidden at DEBUG), a
        // failing or slow one DEBUG. State changes are logged once at INFO/WARN by logIfChanged.
        val durationMs = mark.elapsedNow().inWholeMilliseconds
        val failingOrSlow = state != HealthState.UP || durationMs >= SLOW_CHECK_MS
        log.event(
            if (failingOrSlow) Level.DEBUG else Level.TRACE,
            LogType.EXTERNAL_REQUEST,
            "Dependency checked",
            "dependency" to check.name,
            "status" to state.name,
            "durationMs" to durationMs,
            "error" to cause?.javaClass?.simpleName,
        )
        logIfChanged(check.name, state, cause)
        return state
    }

    /**
     * Logs a dependency going down (WARN, with the exception when the check threw) and coming back (INFO), once each.
     */
    private fun logIfChanged(name: String, state: HealthState, cause: Exception?) {
        val previous = lastState.put(name, state)
        if (previous == state || (previous == null && state == HealthState.UP)) return
        log.event(
            if (state == HealthState.UP) Level.INFO else Level.WARN,
            LogType.EXTERNAL_REQUEST,
            if (state == HealthState.UP) "Dependency healthy again" else "Dependency not healthy",
            "dependency" to name,
            "status" to state.name,
            "previousStatus" to previous?.name,
            cause = cause,
        )
    }

    private companion object {
        /** Slow-check threshold (ADR-0017): a check that takes this long or longer gets a DEBUG line even when UP. */
        const val SLOW_CHECK_MS = 500L
        val log: Logger = LoggerFactory.getLogger(CheckReadiness::class.java)
    }
}
