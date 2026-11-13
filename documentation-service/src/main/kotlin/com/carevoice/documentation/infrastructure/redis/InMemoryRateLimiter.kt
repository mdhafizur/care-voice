package com.carevoice.documentation.infrastructure.redis

import com.carevoice.documentation.application.DistributedRateLimiter
import com.carevoice.documentation.application.RateLimitDecision
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Fixed-window limiter in local memory: the per-instance floor when Redis is down, and the implementation used when
 * no Redis is configured (tests). Windows are aligned to the clock, so all keys roll over together.
 */
class InMemoryRateLimiter(private val clock: Clock = Clock.systemUTC()) : DistributedRateLimiter {
    private data class Window(val start: Long, var count: Long)

    private val windows = ConcurrentHashMap<String, Window>()

    /**
     * Counts the request in this process's memory, in windows aligned to the clock.
     * Ended windows are removed once the map holds more than [MAX_KEYS] keys.
     */
    override suspend fun tryAcquire(scope: String, key: String, limit: Int, window: Duration): RateLimitDecision {
        val now = clock.millis()
        val size = window.inWholeMilliseconds
        val start = now - now % size
        val current =
            windows.compute("$scope:$key") { _, existing ->
                if (existing == null || existing.start != start) Window(start, 1) else existing.also { it.count++ }
            }!!
        // Cheap housekeeping: drop windows that ended, so the map does not grow with every IP ever seen.
        if (windows.size > MAX_KEYS) windows.entries.removeIf { it.value.start < start }
        val resetIn = (start + size - now).coerceAtLeast(1).milliseconds
        return if (current.count <= limit) {
            RateLimitDecision.Allowed(limit - current.count, resetIn)
        } else {
            RateLimitDecision.Rejected(resetIn)
        }
    }

    private companion object {
        /** Number of keys above which [tryAcquire] removes windows that have ended (cheap housekeeping). */
        const val MAX_KEYS = 100_000
    }
}
