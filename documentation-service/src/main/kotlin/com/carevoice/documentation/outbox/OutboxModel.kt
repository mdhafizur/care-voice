package com.carevoice.documentation.outbox

import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning of the [OutboxRelay]. Defaults are the contract values (docs/CONTRACTS.md, section 8).
 *
 * @property batchSize rows claimed per database round trip.
 * @property channelCapacity claimed rows that may wait for a publisher; when full, the claimer suspends (backpressure).
 * @property concurrency publishes running at the same time.
 * @property pollInterval pause between claims when there was not a full batch of work.
 * @property lease how long a claimed row is invisible to other claimers. If this instance dies mid-publish, the row
 *   becomes due again after the lease and another instance takes it over.
 * @property maxAttempts failed publishes before a row may be marked DEAD (together with [retryFor]).
 * @property retryFor minimum time a row keeps retrying after it was written. A retryable failure marks it DEAD only
 *   when BOTH [maxAttempts] are used up AND the row is older than this. Counting attempts alone gave up after about
 *   1.5 minutes of failures (10 attempts, 60 s cap), so an outage longer than that turned valid events DEAD whenever
 *   the circuit breaker did not open first, e.g. on a quiet ward with too few calls to trip it. A row costs one call
 *   per backoff window (at most one a minute), so retrying for a day is cheap.
 * @property backoffBase first retry window (full jitter, see [com.carevoice.documentation.resilience.Backoff]).
 * @property backoffCap largest retry window.
 */
data class OutboxSettings(
    val batchSize: Int = 50,
    val channelCapacity: Int = 100,
    val concurrency: Int = 8,
    val pollInterval: Duration = 500.milliseconds,
    val lease: Duration = 30.seconds,
    val maxAttempts: Int = 10,
    val backoffBase: Duration = 500.milliseconds,
    val backoffCap: Duration = 60.seconds,
    val retryFor: Duration = 24.hours,
) {
    init {
        require(batchSize >= 1) { "batchSize must be >= 1" }
        require(channelCapacity >= 1) { "channelCapacity must be >= 1" }
        require(concurrency >= 1) { "concurrency must be >= 1" }
        require(pollInterval.isPositive()) { "pollInterval must be positive" }
        require(lease.isPositive()) { "lease must be positive" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(backoffBase.isPositive() && backoffCap >= backoffBase) { "backoff needs 0 < base <= cap" }
        require(!retryFor.isNegative()) { "retryFor must not be negative" }
    }
}

/**
 * One outbox row as handed to an [EventPublisher].
 *
 * @property payload the event JSON exactly as stored; publishers send it as-is and never log it.
 * @property attempts failed publish attempts so far (0 on the first try).
 * @property requestId `X-Request-Id` of the nurse's request that produced the event.
 * @property traceparent W3C trace context of that request.
 *
 * Why the correlation travels with the row: the publish happens seconds (or, after an outage, hours) later on a
 * different coroutine, possibly in a different replica. Automatic context propagation (MDC, OpenTelemetry's current
 * span) only follows a call stack, so it cannot connect the two. Persisting the ids with the event can: the relay
 * logs with the original requestId and alert-service receives the original trace, so one search shows the whole path
 * from the nurse's phone to the alert.
 */
data class OutboxEvent(
    val id: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val attempts: Int,
    val requestId: String? = null,
    val traceparent: String? = null,
)

/**
 * What happened to one publish. Publishers report failures as values, not exceptions, so the relay's decision
 * (mark published, retry later, give up) is an exhaustive `when` that the compiler checks.
 */
sealed interface PublishResult {
    /** The consumer has the event (first delivery or an acknowledged duplicate). */
    data object Published : PublishResult

    /** A transient failure (timeout, connection error, 5xx, 429); try again later. */
    data class Retryable(
        val reason: String,
        /** The consumer's `Retry-After`, honoured when it is longer than our own backoff. */
        val retryAfter: Duration? = null,
    ) : PublishResult

    /** The consumer rejected the event itself (4xx); retrying the same bytes can never succeed. */
    data class NonRetryable(val reason: String) : PublishResult

    /** Our circuit breaker did not even try, because the consumer is known to be failing. */
    data object CircuitOpen : PublishResult
}

/** Delivers one event to its consumer. Implementations must be safe to call concurrently. */
fun interface EventPublisher {
    /** Delivers one event; failures come back as a [PublishResult] value, not as exceptions. */
    suspend fun publish(event: OutboxEvent): PublishResult
}
