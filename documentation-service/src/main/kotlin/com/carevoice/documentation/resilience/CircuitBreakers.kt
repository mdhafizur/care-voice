package com.carevoice.documentation.resilience

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Circuit breaker tuning. The defaults are the contract values (docs/CONTRACTS.md, section 8): open when at least
 * 50 % of the last 20 calls failed (and at least 20 calls were made), stay open for 30 s, then let 3 probe calls
 * through (half-open) and close again only if they succeed.
 *
 * Why a breaker at all: when a dependency is down, every call would otherwise wait for its full timeout. The breaker
 * turns that into an immediate "not now", which keeps our own threads, connections and latency free while the
 * dependency recovers, and stops us from hammering a service that is already struggling.
 */
data class CircuitBreakerSettings(
    val failureRateThreshold: Float = 50f,
    val slidingWindowSize: Int = 20,
    val minimumNumberOfCalls: Int = 20,
    val waitDurationInOpenState: Duration = 30.seconds,
    val permittedCallsInHalfOpenState: Int = 3,
)

object CircuitBreakers {
    /**
     * Creates a count-based breaker and binds its state, call and failure-rate meters
     * (`resilience4j_circuitbreaker_*{name=...}`) to [meterRegistry], so its state is visible in Grafana.
     */
    fun create(name: String, settings: CircuitBreakerSettings, meterRegistry: MeterRegistry): CircuitBreaker {
        val config =
            CircuitBreakerConfig
                .custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize)
                .minimumNumberOfCalls(settings.minimumNumberOfCalls)
                .failureRateThreshold(settings.failureRateThreshold)
                .waitDurationInOpenState(settings.waitDurationInOpenState.toJavaDuration())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpenState)
                .build()
        // One registry per breaker keeps instances independent (tests create many clients side by side).
        val registry = CircuitBreakerRegistry.of(config)
        val breaker = registry.circuitBreaker(name)
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry)
        return breaker
    }
}

/** Result of [guard]: either the call ran, or the open breaker rejected it without running it. */
sealed interface Guarded<out T> {
    data class Completed<T>(val value: T) : Guarded<T>

    data object Rejected : Guarded<Nothing>
}

/**
 * Runs a suspending [block] under this breaker and records the outcome.
 *
 * Our HTTP clients return failures as values (a `Retryable` result), not as exceptions, so [isFailure] tells the
 * breaker which values count as failures. This uses Resilience4j's low-level permission API (the same calls its own
 * `executeSuspendFunction` makes), which keeps the classification explicit and visible here.
 *
 * Cancellation is neither a success nor a failure of the dependency: the permission is released without recording
 * anything, otherwise shutting down the service could open the breaker.
 */
suspend fun <T> CircuitBreaker.guard(isFailure: (T) -> Boolean, block: suspend () -> T): Guarded<T> {
    // Step 1: ask the breaker for permission. While it is open, the call is not made at all.
    if (!tryAcquirePermission()) return Guarded.Rejected
    // Step 2: run the call and time it. An exception counts as a failure. A cancel is not a failure, so the
    // permission is only given back.
    val start = System.nanoTime()
    val value =
        try {
            block()
        } catch (e: CancellationException) {
            releasePermission()
            throw e
        } catch (e: Exception) {
            onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e)
            throw e
        }
    // Step 3: a returned value can also count as a failure (isFailure). Report failure or success to the breaker.
    val elapsed = System.nanoTime() - start
    if (isFailure(value)) {
        onError(elapsed, TimeUnit.NANOSECONDS, FailedResult)
    } else {
        onSuccess(elapsed, TimeUnit.NANOSECONDS)
    }
    return Guarded.Completed(value)
}

/**
 * What the breaker records for a call that returned a failure value. Resilience4j's API takes a Throwable; a shared
 * instance without a stack trace costs nothing per call.
 */
private object FailedResult : RuntimeException("call returned a failure result", null, false, false)
