package com.carevoice.documentation.resilience

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Exponential backoff with "full jitter" (AWS Architecture Blog, "Exponential Backoff and Jitter").
 *
 * `delay = random(0, min(cap, base * 2^attempt))`
 *
 * Why full jitter rather than plain exponential or "equal jitter" (`ceiling / 2 + random(0, ceiling / 2)`):
 * when alert-service has an outage, hundreds of outbox rows fail at roughly the same moment. Plain exponential
 * backoff retries them all at the same moment again (a thundering herd on a service that is just recovering).
 * Equal jitter spreads them, but only over the upper half of the window. Full jitter spreads them over the whole
 * window, which in the AWS measurements gave the least total work and the fewest collisions. The price is that an
 * individual retry may come very early; for an asynchronous outbox that is irrelevant.
 *
 * Pure function with an injectable [Random], so tests can pin the jitter and assert exact values.
 */
object Backoff {
    /**
     * The upper bound of the jitter window for [attempt] (0-based): `min(cap, base * 2^attempt)`.
     *
     * Overflow safety: `base * 2^attempt` overflows a Long after a few dozen attempts. Instead of multiplying first,
     * the cap is shifted right and compared: `base > cap >> attempt` is exactly `base * 2^attempt > cap`, and when
     * it is false the shifted value is at most `cap`, so it cannot overflow.
     */
    fun ceiling(attempt: Int, base: Duration, cap: Duration): Duration {
        validate(attempt, base, cap)
        val baseNanos = base.inWholeNanoseconds
        val capNanos = cap.inWholeNanoseconds
        // The JVM masks shift distances to 6 bits (x shl 64 == x), so attempts of 63 and more are answered directly:
        // any base of at least 1 ns doubled 63 times is >= 2^63 ns, which exceeds every cap validate() admits.
        if (attempt >= MAX_SHIFT || baseNanos > (capNanos shr attempt)) return cap
        return (baseNanos shl attempt).nanoseconds
    }

    /** A random delay in `[0, ceiling(attempt, base, cap)]`, both bounds inclusive. */
    fun fullJitter(attempt: Int, base: Duration, cap: Duration, random: Random = Random.Default): Duration {
        val ceilingNanos = ceiling(attempt, base, cap).inWholeNanoseconds
        // nextLong's upper bound is exclusive; +1 makes the ceiling itself reachable. The finite-cap check in
        // validate() guarantees ceilingNanos < Long.MAX_VALUE, so the addition cannot overflow.
        return random.nextLong(0, ceilingNanos + 1).nanoseconds
    }

    /** Rejects a negative attempt, a non-positive base, a cap below base, and a cap too large to calculate with. */
    private fun validate(attempt: Int, base: Duration, cap: Duration) {
        require(attempt >= 0) { "attempt must be >= 0 but was $attempt" }
        require(base.isPositive()) { "base must be positive but was $base" }
        require(cap >= base) { "cap ($cap) must not be smaller than base ($base)" }
        // inWholeNanoseconds saturates at Long.MAX_VALUE for infinite and very large durations (~292 years);
        // excluding that value keeps all arithmetic above exact.
        require(cap.inWholeNanoseconds < Long.MAX_VALUE) { "cap must be finite and below 292 years but was $cap" }
    }

    /**
     * A Long has 64 bits, and the JVM uses only the low 6 bits of a shift distance (`x shl 64 == x`). So from 63
     * shifts on, [ceiling] returns the cap directly instead of shifting.
     */
    private const val MAX_SHIFT = 63
}
