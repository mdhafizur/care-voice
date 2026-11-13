package com.carevoice.documentation.infrastructure.redis

import com.carevoice.documentation.application.DistributedRateLimiter
import com.carevoice.documentation.application.RateLimitDecision
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import io.lettuce.core.ScriptOutputType
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Adapter: fixed-window rate limiting with counters in Redis, shared by every replica (docs/CONTRACTS.md, section 9a).
 *
 * One Lua script does INCR + PEXPIRE (on the first hit) + PTTL atomically on the server, so concurrent requests on
 * different pods can never both see "the last free slot". Keys: `carevoice:ratelimit:<scope>:<key>`.
 *
 * Fails OPEN: when Redis is unavailable the request is allowed (availability of documentation for nurses beats a
 * strict limit), with a WARN log and the `carevoice_ratelimit_fail_open_total` counter. Scopes listed in
 * [floorScopes] (login per IP) then fall back to [fallback], an in-memory per-instance limit, so brute-forcing
 * passwords never becomes unlimited.
 */
class RedisRateLimiter(
    private val redis: RedisConnections,
    meterRegistry: MeterRegistry,
    private val fallback: InMemoryRateLimiter,
    private val floorScopes: Set<String> = emptySet(),
) : DistributedRateLimiter {
    private val log = LoggerFactory.getLogger(RedisRateLimiter::class.java)
    private val failOpen =
        Counter
            .builder(FAIL_OPEN_METRIC)
            .description("Rate-limit checks allowed because Redis was unavailable")
            .register(meterRegistry)

    /**
     * Counts the request in Redis with one atomic script and allows it while the count is within [limit].
     * When Redis fails or is not connected, it allows the request, except for [floorScopes], which use [fallback].
     */
    override suspend fun tryAcquire(scope: String, key: String, limit: Int, window: Duration): RateLimitDecision {
        // Step 1: get the Redis connection. It is null while Redis is not reachable.
        val connection = redis.commandsOrNull()
        if (connection != null) {
            try {
                // Step 2: run the counting script in Redis. It counts this request and returns the count and
                // the time left in the window.
                val result =
                    connection
                        .async()
                        .eval<List<Long>>(
                            SCRIPT,
                            ScriptOutputType.MULTI,
                            arrayOf("$KEY_PREFIX$scope:$key"),
                            window.inWholeMilliseconds.toString(),
                        )
                        .await()
                // Step 3: allow the request while the count is within the limit, otherwise reject it.
                val count = result[0]
                val ttl = result[1].coerceAtLeast(1).milliseconds
                val decision: RateLimitDecision =
                    if (count <= limit) {
                        RateLimitDecision.Allowed(limit - count, ttl)
                    } else {
                        RateLimitDecision.Rejected(ttl)
                    }
                return decision.also { logDecision(scope, "redis", it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.event(
                    Level.WARN,
                    LogType.EXTERNAL_REQUEST,
                    "Rate limiter failing open: Redis error",
                    "scope" to scope,
                    "error" to e.javaClass.simpleName,
                )
            }
        } else {
            log.event(
                Level.WARN,
                LogType.EXTERNAL_REQUEST,
                "Rate limiter failing open: Redis unavailable",
                "scope" to scope,
            )
        }
        // Step 4: Redis failed, so fail open (let the request through). Floor scopes still get
        // the in-memory limit of this instance.
        failOpen.increment()
        return if (scope in floorScopes) {
            fallback.tryAcquire(scope, key, limit, window).also { logDecision(scope, "memory-floor", it) }
        } else {
            RateLimitDecision.Allowed(limit.toLong(), window).also { logDecision(scope, "fail-open", it) }
        }
    }

    /** DEBUG: the decision per check. The key (username or client IP) is never logged, only its scope. */
    private fun logDecision(scope: String, store: String, decision: RateLimitDecision) {
        when (decision) {
            is RateLimitDecision.Allowed -> log.event(
                Level.DEBUG,
                LogType.BUSINESS,
                "Rate limit checked",
                "scope" to scope,
                "store" to store,
                "allowed" to true,
                "remaining" to decision.remaining,
                "resetInMs" to decision.resetIn.inWholeMilliseconds,
            )

            is RateLimitDecision.Rejected -> log.event(
                Level.DEBUG,
                LogType.BUSINESS,
                "Rate limit checked",
                "scope" to scope,
                "store" to store,
                "allowed" to false,
                "retryAfterMs" to decision.retryAfter.inWholeMilliseconds,
            )
        }
    }

    companion object {
        const val KEY_PREFIX = "carevoice:ratelimit:"
        const val FAIL_OPEN_METRIC = "carevoice.ratelimit.fail.open"

        /**
         * Returns {count, pttl}. PEXPIRE only on the first hit, so the window is fixed, not sliding.
         *
         * KEYS[1] is the counter key, ARGV[1] the window length in milliseconds. INCR adds 1 (a new key starts at 1),
         * PEXPIRE sets the expiry in milliseconds, and PTTL returns the milliseconds left, or a negative number when
         * the key has no expiry (-1) or does not exist (-2). In that case the script sets the expiry again, so a
         * counter can never live forever. Redis commands: https://redis.io/docs/latest/commands/
         */
        internal val SCRIPT =
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {count, ttl}
            """.trimIndent()
    }
}
