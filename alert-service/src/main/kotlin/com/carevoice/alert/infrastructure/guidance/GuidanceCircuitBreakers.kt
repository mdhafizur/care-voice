package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.Embedder
import com.carevoice.alert.application.GuidanceGenerator
import com.carevoice.alert.application.GuidanceStep
import com.carevoice.alert.application.GuidanceWriter
import com.carevoice.alert.domain.guidance.ScoredChunk
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Circuit breaker tuning for the guidance providers. Smaller windows than documentation-service's (20 calls), because
 * guidance traffic is low (a nurse opening an alert): the breaker must be able to open after a handful of failures.
 */
data class GuidanceBreakerSettings(
    val failureRateThreshold: Float = 50f,
    val slidingWindowSize: Int = 10,
    val minimumNumberOfCalls: Int = 5,
    val waitDurationInOpenState: Duration = 30.seconds,
    val permittedCallsInHalfOpenState: Int = 2,
)

/**
 * One count-based breaker per provider call site (`guidance-writer`, `guidance-embedder`), with its state exported as
 * `resilience4j_circuitbreaker_*{name=...}`. The open-state exception carries no stack trace: while a provider is down
 * it is thrown on every request and logged by the caller's fallback.
 */
object GuidanceCircuitBreakers {
    const val WRITER = "guidance-writer"
    const val EMBEDDER = "guidance-embedder"

    /**
     * Builds a count-based breaker named [name] and exports its metrics to [registry].
     * A circuit breaker stops calling a failing service for a while, so callers fail fast instead of waiting.
     */
    fun create(
        name: String,
        registry: MeterRegistry,
        settings: GuidanceBreakerSettings = GuidanceBreakerSettings(),
    ): CircuitBreaker {
        val config =
            CircuitBreakerConfig
                .custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize)
                .minimumNumberOfCalls(settings.minimumNumberOfCalls)
                .failureRateThreshold(settings.failureRateThreshold)
                .waitDurationInOpenState(settings.waitDurationInOpenState.toJavaDuration())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpenState)
                .writableStackTraceEnabled(false)
                .build()
        // One registry per breaker keeps instances independent (tests create several side by side).
        val breakers = CircuitBreakerRegistry.of(config)
        val breaker = breakers.circuitBreaker(name)
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(registry)
        return breaker
    }
}

private val breakerLog = LoggerFactory.getLogger("com.carevoice.alert.infrastructure.guidance.GuidanceCircuitBreakers")

/**
 * Runs [block] under this breaker. An open breaker throws [CallNotPermittedException] at once instead of waiting for
 * the provider's timeout (15 to 20 s). Exceptions count as failures; a normal return (including a writer declining with
 * null) is a success. Cancellation is neither: the permission is released without recording, so a shutdown or a
 * request deadline never opens the breaker.
 */
internal suspend fun <T> CircuitBreaker.guard(block: suspend () -> T): T {
    if (!tryAcquirePermission()) {
        breakerLog.event(
            Level.DEBUG,
            LogType.EXTERNAL_REQUEST,
            "Guidance provider skipped: circuit open",
            "breaker" to name,
        )
        throw CallNotPermittedException.createCallNotPermittedException(this)
    }
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
    onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS)
    return value
}

/**
 * Decorator: a circuit breaker around an LLM [GuidanceWriter]. While the provider fails, GetAlertGuidance gets the
 * open-breaker exception immediately and serves the extractive guidance, instead of every nurse waiting for the
 * provider's timeout first.
 */
class CircuitBreakingGuidanceWriter(private val delegate: GuidanceWriter, val circuitBreaker: CircuitBreaker) :
    GuidanceWriter {
    override val generator: GuidanceGenerator = delegate.generator

    /** Passes the call through the breaker; an open breaker throws at once. */
    override suspend fun write(situation: String, sources: List<ScoredChunk>): List<GuidanceStep>? =
        circuitBreaker.guard { delegate.write(situation, sources) }
}

/**
 * Decorator: a circuit breaker around a remote [Embedder]. Without embeddings there is no retrieval, so the guidance
 * endpoint answers 503 either way; the breaker makes that answer immediate while the provider is down.
 */
class CircuitBreakingEmbedder(private val delegate: Embedder, val circuitBreaker: CircuitBreaker) : Embedder {
    override val model: String = delegate.model
    override val minScore: Double = delegate.minScore

    /** Passes the call through the breaker; an open breaker throws at once. */
    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
        circuitBreaker.guard { delegate.embedDocuments(texts) }

    /** Passes the call through the breaker; an open breaker throws at once. */
    override suspend fun embedQuery(text: String): FloatArray = circuitBreaker.guard { delegate.embedQuery(text) }

    /** Uses the wrapped embedder's text, so logs show the real provider. */
    override fun toString(): String = delegate.toString()
}
