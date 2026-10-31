package com.carevoice.alert.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.carevoice.alert.domain.WardScope
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.event.Level

/** The authenticated caller, built from the JWT claims (docs/CONTRACTS.md, section 5). */
data class CallerPrincipal(val username: String, val tenantId: String, val wardId: String, val roles: Set<Role>) {
    val scope: WardScope get() = WardScope(tenantId, wardId)
}

enum class Role { NURSE, WARD_LEAD, SERVICE }

/** JWT verification settings; mirrors `JwtConfig` without coupling the API layer to the config package. */
data class JwtSettings(val secret: ByteArray, val issuer: String, val audience: String) {
    override fun equals(other: Any?): Boolean = other is JwtSettings &&
        secret.contentEquals(other.secret) &&
        issuer == other.issuer &&
        audience == other.audience

    override fun hashCode(): Int = arrayOf<Any>(secret.contentHashCode(), issuer, audience).contentHashCode()

    override fun toString(): String = "JwtSettings(secret=***, issuer=$issuer, audience=$audience)"
}

const val JWT_AUTH = "jwt"
private const val REALM = "carevoice"
private const val LEEWAY_SECONDS = 5L

private val authLog = LoggerFactory.getLogger("com.carevoice.alert.api.Authentication")

/**
 * HS256 bearer authentication. Every failure (missing, malformed, expired token, wrong signature, issuer or audience,
 * missing claims) gives the same 401 problem with `WWW-Authenticate: Bearer`, so the response reveals nothing about
 * which check failed.
 */
fun AuthenticationConfig.configureJwt(settings: JwtSettings) {
    jwt(JWT_AUTH) {
        realm = REALM
        verifier(
            JWT
                .require(Algorithm.HMAC256(settings.secret))
                .withIssuer(settings.issuer)
                .withAudience(settings.audience)
                .acceptLeeway(LEEWAY_SECONDS)
                .build(),
        )
        validate { credential ->
            val payload = credential.payload
            val subject = payload.subject?.takeIf { it.isNotBlank() }
            val tenant = payload.getClaim("tenant").asString()?.takeIf { it.isNotBlank() }
            val ward = payload.getClaim("ward").asString()?.takeIf { it.isNotBlank() }
            val roles =
                runCatching { payload.getClaim("roles").asList(String::class.java) }
                    .getOrNull()
                    .orEmpty()
                    .mapNotNull { name -> Role.entries.firstOrNull { it.name == name } }
                    .toSet()
            if (subject == null || tenant == null || ward == null || payload.expiresAt == null) {
                null
            } else {
                CallerPrincipal(subject, tenant, ward, roles)
            }
        }
        challenge { _, _ ->
            // Missing vs refused token (bad signature, expired, wrong audience, missing claims); never the token.
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
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"$REALM\"")
            call.respondProblem(
                HttpStatusCode.Unauthorized,
                "unauthorized",
                "Unauthorized",
                "A valid bearer token is required.",
            )
        }
    }
}

/**
 * Authenticated routes. The order the contract prescribes falls out of the pipeline: authentication (401) → role
 * check (403, [roleRoute]) → the handler, which looks the resource up within the caller's scope (404) and then
 * applies business rules. [PrincipalMdc] is installed once here for every authenticated route.
 */
fun Route.authenticated(build: Route.() -> Unit): Route = authenticate(JWT_AUTH) {
    install(PrincipalMdc)
    build()
}

/** A sub-route under [path] that requires one of [roles]; use inside [authenticated]. */
fun Route.roleRoute(path: String, vararg roles: Role, build: Route.() -> Unit): Route = route(path) {
    install(RequireRole) { allowed = roles.toSet() }
    build()
}

/** The authenticated caller; only valid inside [authorized] routes. */
val ApplicationCall.caller: CallerPrincipal
    get() = principal<CallerPrincipal>() ?: error("No authenticated principal on this route")

class RequireRoleConfig {
    var allowed: Set<Role> = emptySet()
}

/** Route-scoped role check, run right after authentication succeeded. */
val RequireRole =
    createRouteScopedPlugin("RequireRole", ::RequireRoleConfig) {
        val allowed = pluginConfig.allowed
        on(AuthenticationChecked) { call ->
            val principal = call.principal<CallerPrincipal>() ?: return@on
            if (principal.roles.none { it in allowed }) {
                throw ApiException.Forbidden("This operation requires the role ${allowed.joinToString(" or ")}.")
            }
        }
    }

private val PrincipalMdcKey = AttributeKey<Map<String, String>>("PrincipalMdc")

/**
 * Puts `userId`, `tenantId` and `wardId` into the logging MDC for the rest of the call. They are unknown when the call
 * starts (CallLogging's MDC is computed before authentication), so this route-scoped plugin hooks on
 * AuthenticationChecked. Because MDC is thread-local and coroutines hop threads, the values are also carried by an
 * `MDCContext` that wraps the remaining pipeline ([CallPhaseWithMdc]); a bare `MDC.put` would be lost at the next
 * suspension point.
 */
val PrincipalMdc =
    createRouteScopedPlugin("PrincipalMdc") {
        on(AuthenticationChecked) { call ->
            val principal = call.principal<CallerPrincipal>() ?: return@on
            val values =
                mapOf("userId" to principal.username, "tenantId" to principal.tenantId, "wardId" to principal.wardId)
            call.attributes.put(PrincipalMdcKey, values)
            values.forEach { (key, value) -> MDC.put(key, value) }
        }
        on(CallPhaseWithMdc) { call, proceed ->
            val extra = call.attributes.getOrNull(PrincipalMdcKey)
            if (extra == null) {
                proceed()
            } else {
                withContext(MDCContext(MDC.getCopyOfContextMap().orEmpty() + extra)) { proceed() }
            }
        }
    }

/** Wraps everything from just before the handler phase onwards, i.e. after authentication and the role check. */
private object CallPhaseWithMdc : Hook<suspend (ApplicationCall, suspend () -> Unit) -> Unit> {
    private val phase = PipelinePhase("PrincipalMdc")

    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall, suspend () -> Unit) -> Unit,
    ) {
        pipeline.insertPhaseBefore(ApplicationCallPipeline.Call, phase)
        pipeline.intercept(phase) { handler(context) { proceed() } }
    }
}
