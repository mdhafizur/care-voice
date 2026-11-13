package com.carevoice.documentation.application

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

data class ReadinessReport(val ready: Boolean, val overall: HealthStatus, val checks: List<HealthCheckResult>)

data class HealthCheckResult(
    val name: String,
    val status: HealthStatus,
    /** When true, [HealthStatus.DOWN] makes the whole service not ready (503); otherwise it only degrades. */
    val critical: Boolean,
)

/**
 * Use case: readiness for the load balancer (`GET /readyz`).
 *
 * Only critical dependencies (the primary database) make the service not ready. Redis and RabbitMQ only DEGRADE it:
 * rate limits fail open, caches fall back to TTLs, and the outbox buffers events until the broker is back, so nurses
 * can keep documenting (docs/CONTRACTS.md, sections 8a and 9a).
 *
 * [markShuttingDown] flips readiness to 503 FIRST on SIGTERM, so the load balancer stops routing new requests here
 * before in-flight ones are drained (docs/CONTRACTS.md, section 9b).
 */
class Readiness(private val probes: List<HealthProbe>) {
    private val shuttingDown = AtomicBoolean(false)

    /** Last status per probe, so a change is logged once instead of on every 5-second probe. */
    private val lastStatus = ConcurrentHashMap<String, HealthStatus>()

    /** Makes every later [check] report not ready. There is no way back: it is only called at shutdown. */
    fun markShuttingDown() {
        shuttingDown.set(true)
    }

    val isShuttingDown: Boolean get() = shuttingDown.get()

    /** Runs all probes in parallel. Ready only when no critical probe is DOWN and no shutdown has started. */
    suspend fun check(): ReadinessReport {
        // Step 1: run all probes at the same time. Each one is timed and logged; a probe that throws gets
        // its fallback status.
        val results = coroutineScope { probes.map { probe -> async { checkProbe(probe) } }.awaitAll() }
        // Step 2: decide readiness, then the overall status. DEGRADED means still ready, but a non-critical
        // probe is not UP.
        val criticalDown = results.any { it.critical && it.status == HealthStatus.DOWN }
        val ready = !criticalDown && !isShuttingDown
        val overall =
            when {
                !ready -> HealthStatus.DOWN
                results.any { it.status != HealthStatus.UP } -> HealthStatus.DEGRADED
                else -> HealthStatus.UP
            }
        // Step 3: return the report; during shutdown, also log why it is DOWN.
        if (isShuttingDown) log.event(Level.DEBUG, LogType.BUSINESS, "Readiness DOWN: shutting down")
        return ReadinessReport(ready, overall, results)
    }

    /** Runs one probe, times it and logs the result. A probe that throws gets its [fallback] status. */
    private suspend fun checkProbe(probe: HealthProbe): HealthCheckResult {
        val mark = TimeSource.Monotonic.markNow()
        val (status, cause) =
            try {
                probe.check() to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fallback(probe) to e
            }
        // /readyz runs every few seconds per pod: a healthy, fast check is TRACE (hidden at DEBUG), a failing or
        // slow one DEBUG. State changes are logged once at INFO/WARN by logIfChanged.
        val durationMs = mark.elapsedNow().inWholeMilliseconds
        val failingOrSlow = status != HealthStatus.UP || durationMs >= SLOW_CHECK_MS
        log.event(
            if (failingOrSlow) Level.DEBUG else Level.TRACE,
            LogType.EXTERNAL_REQUEST,
            "Dependency checked",
            "dependency" to probe.name,
            "status" to status.name,
            "critical" to probe.critical,
            "durationMs" to durationMs,
            "error" to cause?.javaClass?.simpleName,
        )
        logIfChanged(probe, status, cause)
        return HealthCheckResult(probe.name, status, probe.critical)
    }

    /** The status used when a probe throws: DOWN for a critical dependency, DEGRADED for the others. */
    private fun fallback(probe: HealthProbe): HealthStatus =
        if (probe.critical) HealthStatus.DOWN else HealthStatus.DEGRADED

    /**
     * Logs a dependency going down (WARN, with the exception when the probe threw) and coming back (INFO), once per
     * change. A dependency that is UP from the start is not logged.
     */
    private fun logIfChanged(probe: HealthProbe, status: HealthStatus, cause: Exception?) {
        val previous = lastStatus.put(probe.name, status)
        if (previous == status || (previous == null && status == HealthStatus.UP)) return
        log.event(
            if (status == HealthStatus.UP) Level.INFO else Level.WARN,
            LogType.EXTERNAL_REQUEST,
            if (status == HealthStatus.UP) "Dependency healthy again" else "Dependency not healthy",
            "dependency" to probe.name,
            "status" to status.name,
            "previousStatus" to previous?.name,
            "critical" to probe.critical,
            cause = cause,
        )
    }

    private companion object {
        /** The slow-query threshold: a dependency check this slow is worth a DEBUG line even when UP. */
        const val SLOW_CHECK_MS = 500L
        val log: Logger = LoggerFactory.getLogger(Readiness::class.java)
    }
}
