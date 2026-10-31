package com.carevoice.documentation.resilience

import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration

/**
 * How often and how patiently a synchronous call is retried.
 *
 * @property maxAttempts total number of attempts including the first one; 1 means "never retry".
 * @property random injectable so tests can pin the jitter.
 */
data class RetryPolicy(
    val maxAttempts: Int,
    val base: Duration,
    val cap: Duration,
    val random: Random = Random.Default,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1 but was $maxAttempts" }
        require(base.isPositive()) { "base must be positive but was $base" }
        require(cap >= base) { "cap ($cap) must not be smaller than base ($base)" }
    }
}

/**
 * Runs [block] until it returns a result that is not retryable, or [RetryPolicy.maxAttempts] is used up, waiting
 * [Backoff.fullJitter] between attempts. Returns the last result either way, so the caller decides what an exhausted
 * retry means (the AI client degrades to "no AI result", for example).
 *
 * Result-based on purpose: callers turn every expected failure (HTTP status, timeout, connection error) into a value
 * first, so "is this worth retrying?" is an ordinary, unit-testable function instead of exception-type matching.
 * Exceptions thrown by [block] are NOT retried and propagate unchanged; in particular a [CancellationException]
 * always ends the loop, so a retry never outlives the coroutine that asked for it. The waits use [delay], which is
 * cancellable as well.
 *
 * Only use this for short, synchronous retries where a caller is waiting. Long-running retries belong to the outbox
 * relay, which persists the attempt count and schedule in the database so they survive restarts.
 *
 * @param block receives the 0-based attempt number.
 */
suspend fun <T> retry(policy: RetryPolicy, isRetryable: (T) -> Boolean, block: suspend (attempt: Int) -> T): T {
    var attempt = 0
    while (true) {
        val result = block(attempt)
        if (!isRetryable(result) || attempt + 1 >= policy.maxAttempts) return result
        delay(Backoff.fullJitter(attempt, policy.base, policy.cap, policy.random))
        attempt++
    }
}
