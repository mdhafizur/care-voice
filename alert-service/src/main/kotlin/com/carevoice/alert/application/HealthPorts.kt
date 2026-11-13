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
 * Port for one dependency the readiness probe checks (MongoDB primary, RabbitMQ consumer). Adapters live in
 * `infrastructure/`, so the API layer can report readiness without importing a driver.
 */
interface DependencyHealthCheck {
    /** Key in the readiness body, e.g. `mongo` or `rabbitmq`. */
    val name: String

    /**
     * True when the dependency is usable right now; must return quickly. May throw: [CheckReadiness] counts that as
     * DOWN and logs the cause once.
     */
    suspend fun isHealthy(): Boolean
}

enum class HealthState { UP, DOWN }

data class ReadinessReport(val ready: Boolean, val checks: Map<String, HealthState>)

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
        // Step 1: run every dependency check and time it. A check that throws counts as DOWN.
        val results =
            checks.associate { check ->
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
                log.event(
                    if (state != HealthState.UP || durationMs >= SLOW_CHECK_MS) Level.DEBUG else Level.TRACE,
                    LogType.EXTERNAL_REQUEST,
                    "Dependency checked",
                    "dependency" to check.name,
                    "status" to state.name,
                    "durationMs" to durationMs,
                    "error" to cause?.javaClass?.simpleName,
                )
                logIfChanged(check.name, state, cause)
                check.name to state
            }
        // Step 3: build the report. Ready means: not shutting down and every check is UP.
        if (shuttingDown) log.event(Level.DEBUG, LogType.BUSINESS, "Readiness DOWN: shutting down")
        return ReadinessReport(
            ready = !shuttingDown && results.values.all { it == HealthState.UP },
            checks = results,
        )
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
        /** The slow-query threshold: a dependency check this slow is worth a DEBUG line even when UP. */
        const val SLOW_CHECK_MS = 500L
        val log: Logger = LoggerFactory.getLogger(CheckReadiness::class.java)
    }
}
