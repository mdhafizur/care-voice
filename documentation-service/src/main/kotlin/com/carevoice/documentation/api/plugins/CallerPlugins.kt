package com.carevoice.documentation.api.plugins

import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.application.Caller
import com.carevoice.documentation.application.DistributedRateLimiter
import com.carevoice.documentation.application.RateLimitDecision
import com.carevoice.documentation.application.Role
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.response.header
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import kotlin.time.Duration.Companion.minutes

/**
 * Runs [handler] around the rest of a route's pipeline (the `Call` phase, i.e. after authentication), so the handler
 * can wrap the route handler in a coroutine context such as an MDC.
 */
private object AroundCallHook : Hook<suspend (ApplicationCall, suspend () -> Unit) -> Unit> {
    /** Intercepts the `Call` phase and gives [handler] the rest of the pipeline as its `proceed` function. */
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall, suspend () -> Unit) -> Unit,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Call) { handler(call) { proceed() } }
    }
}

/**
 * Puts `userId`, `tenantId` and `wardId` into the log MDC once the caller is known (they are unknown at call start).
 * Hooked on [AuthenticationChecked]; the MDC is then carried with [MDCContext] around the route handler, because the
 * MDC is thread-local and a suspended handler may resume on another thread.
 */
val AuthenticatedMdc =
    createRouteScopedPlugin("AuthenticatedMdc") {
        on(AuthenticationChecked) { call ->
            call.callerOrNull()?.let { putCallerMdc(it) }
        }
        on(AroundCallHook) { call, proceed ->
            val caller = call.callerOrNull()
            if (caller == null) {
                proceed()
            } else {
                putCallerMdc(caller)
                withContext(MDCContext()) { proceed() }
            }
        }
    }

/** Sets `userId`, `tenantId` and `wardId` in the MDC (per-thread fields added to every log line). */
private fun putCallerMdc(caller: Caller) {
    MDC.put("userId", caller.username)
    MDC.put("tenantId", caller.tenantId)
    MDC.put("wardId", caller.wardId)
}

class RoleGuardConfig {
    var allowed: Set<Role> = Role.CARE_STAFF
}

/**
 * 403 before the handler runs. The use cases check roles too; this route-level check is what lets streaming
 * endpoints (SSE), whose response starts as soon as the handler runs, still answer 403 as a normal problem response.
 */
val RoleGuard =
    createRouteScopedPlugin("RoleGuard", ::RoleGuardConfig) {
        val allowed = pluginConfig.allowed
        on(AroundCallHook) { call, proceed ->
            val caller = call.caller()
            if (!caller.hasAnyRole(allowed)) throw ApplicationError.Forbidden()
            proceed()
        }
    }

class UserRateLimitConfig {
    lateinit var limiter: DistributedRateLimiter

    /** Requests per signed-in user per minute; 120 is the `RATE_LIMIT_PER_MINUTE` default. */
    var perMinute: Int = 120
}

/**
 * Per-user rate limit (docs/CONTRACTS.md, sections 9a and 11), backed by the [DistributedRateLimiter] port so the
 * limit holds across replicas. Ktor's built-in RateLimit plugin keeps its buckets in process memory, which would
 * multiply the effective limit by the number of pods; this plugin keeps Ktor's model (route-scoped, after
 * authentication, 429 + `Retry-After`) with shared counters.
 */
val UserRateLimit =
    createRouteScopedPlugin("UserRateLimit", ::UserRateLimitConfig) {
        val limiter = pluginConfig.limiter
        val perMinute = pluginConfig.perMinute
        on(AroundCallHook) { call, proceed ->
            val caller = call.callerOrNull()
            if (caller != null) {
                val decision = limiter.tryAcquire("user", "${caller.tenantId}:${caller.username}", perMinute, 1.minutes)
                when (decision) {
                    is RateLimitDecision.Rejected -> throw ApplicationError.TooManyRequests(decision.retryAfter)

                    is RateLimitDecision.Allowed -> call.response.header(
                        RATE_LIMIT_REMAINING,
                        decision.remaining.toString(),
                    )
                }
            }
            proceed()
        }
    }

/** Response header: how many requests the caller has left in the current one-minute window. */
const val RATE_LIMIT_REMAINING = "X-RateLimit-Remaining"
