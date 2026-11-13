package com.carevoice.documentation.api.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.carevoice.documentation.api.BEARER_CHALLENGE
import com.carevoice.documentation.api.respondProblem
import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.application.Caller
import com.carevoice.documentation.application.DistributedRateLimiter
import com.carevoice.documentation.application.JwtClaims
import com.carevoice.documentation.application.RateLimitDecision
import com.carevoice.documentation.application.Role
import com.carevoice.documentation.config.JwtConfig
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.path
import io.ktor.server.response.header
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.event.Level
import java.time.Clock
import kotlin.time.Duration.Companion.minutes

const val JWT_AUTH = "jwt"

/** The authenticated caller as Ktor principal; routes hand [caller] to the use cases. */
data class CallerPrincipal(val caller: Caller)

/** The caller of an authenticated route; 401 when called outside `authenticate(JWT_AUTH)`. */
fun ApplicationCall.caller(): Caller = principal<CallerPrincipal>()?.caller ?: throw ApplicationError.Unauthorized()

/**
 * JWT bearer authentication (HS256, issuer + audience + required claims). Invalid, expired or incomplete tokens all
 * get the same 401 problem with `WWW-Authenticate: Bearer`; the reason is never revealed to the client.
 *
 * [clock] makes expiry checks deterministic in tests.
 */
private val authLog = LoggerFactory.getLogger("com.carevoice.documentation.api.Authentication")

/**
 * Installs bearer token checks under the name [JWT_AUTH] (rules in the comment above [authLog]).
 * A token must carry `sub`, tenant, ward and roles; role names that are not known are dropped.
 */
fun Application.installJwtAuthentication(config: JwtConfig, clock: Clock) {
    val tokenVerifier = jwtVerifier(config, clock)
    install(Authentication) {
        jwt(JWT_AUTH) {
            realm = "carevoice"
            verifier(tokenVerifier)
            // validate: runs after the verifier has accepted the token. It turns the claims into the caller.
            validate { credential ->
                val payload = credential.payload

                // A signed token without a required claim is refused; DEBUG names the claim (never its value).
                fun missing(claim: String): Nothing? {
                    authLog.event(Level.DEBUG, LogType.REQUEST, "Token refused: claim missing", "claim" to claim)
                    return null
                }
                // Read the required claims: subject (the user), tenant and ward.
                val subject = payload.subject?.takeIf { it.isNotBlank() } ?: return@validate missing("sub")
                val tenant =
                    payload.getClaim(JwtClaims.TENANT).asString()?.takeIf { it.isNotBlank() }
                        ?: return@validate missing(JwtClaims.TENANT)
                val ward =
                    payload.getClaim(JwtClaims.WARD).asString()?.takeIf { it.isNotBlank() }
                        ?: return@validate missing(JwtClaims.WARD)
                // Roles are optional. Unknown role names are dropped.
                val roles = payload.getClaim(
                    JwtClaims.ROLES,
                ).asList(String::class.java)?.mapNotNull(Role::parse)?.toSet().orEmpty()
                CallerPrincipal(Caller(subject, tenant, ward, roles))
            }
            // challenge: runs when no valid token was found. It logs why and answers 401 Unauthorized.
            challenge { _, _ ->
                // Missing vs refused token (bad signature, expired, wrong audience, missing claims). The token itself
                // is never logged.
                val reason =
                    if (call.authentication.allFailures.all { it == AuthenticationFailedCause.NoCredentials }) {
                        "missing-token"
                    } else {
                        "invalid-token"
                    }
                authLog.event(
                    Level.INFO,
                    LogType.REQUEST,
                    "Authentication failed",
                    "reason" to reason,
                    "path" to call.request.path().take(256),
                )
                call.response.header(HttpHeaders.WWWAuthenticate, BEARER_CHALLENGE)
                call.respondProblem(
                    HttpStatusCode.Unauthorized,
                    "unauthorized",
                    "Unauthorized",
                    "A valid bearer token is required.",
                )
            }
        }
    }
}

/** Same rules as the issuer: HS256, issuer, audience, required claims, no leeway. */
fun jwtVerifier(config: JwtConfig, clock: Clock): JWTVerifier {
    val verification =
        JWT
            .require(Algorithm.HMAC256(config.secret))
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withClaimPresence(JwtClaims.TENANT)
            .withClaimPresence(JwtClaims.WARD)
            .withClaimPresence(JwtClaims.ROLES)
            .acceptLeeway(0)
    return (verification as JWTVerifier.BaseVerification).build(clock)
}

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
            call.principal<CallerPrincipal>()?.caller?.let { putCallerMdc(it) }
        }
        on(AroundCallHook) { call, proceed ->
            val caller = call.principal<CallerPrincipal>()?.caller
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
            val caller = call.principal<CallerPrincipal>()?.caller ?: throw ApplicationError.Unauthorized()
            if (!caller.hasAnyRole(allowed)) throw ApplicationError.Forbidden()
            proceed()
        }
    }

class UserRateLimitConfig {
    lateinit var limiter: DistributedRateLimiter
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
            val caller = call.principal<CallerPrincipal>()?.caller
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

const val RATE_LIMIT_REMAINING = "X-RateLimit-Remaining"

/**
 * Security headers on every response (docs/CONTRACTS.md, section 11). `Cache-Control: no-store` unless a handler
 * set its own (conditional GETs use `private, no-cache`: cacheable by the client, but always revalidated).
 * No CORS plugin on purpose: clients are servers and mobile apps, never browsers on another origin.
 */
fun Application.installSecurityHeaders() {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "no-referrer")
    }
    install(
        createApplicationPlugin("CacheControlDefault") {
            onCallRespond { call, _ ->
                if (call.response.headers[HttpHeaders.CacheControl] == null) {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                }
            }
        },
    )
}
