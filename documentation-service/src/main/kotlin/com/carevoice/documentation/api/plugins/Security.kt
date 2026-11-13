package com.carevoice.documentation.api.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.carevoice.documentation.api.BEARER_CHALLENGE
import com.carevoice.documentation.api.loggedPath
import com.carevoice.documentation.api.respondProblem
import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.application.Caller
import com.carevoice.documentation.application.JwtClaims
import com.carevoice.documentation.application.Role
import com.carevoice.documentation.config.JwtConfig
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock

const val JWT_AUTH = "jwt"

/** The authenticated caller as Ktor principal; routes hand [caller] to the use cases. */
data class CallerPrincipal(val caller: Caller)

/** The caller of an authenticated route, or null when the request carries no accepted token. */
internal fun ApplicationCall.callerOrNull(): Caller? = principal<CallerPrincipal>()?.caller

/** The caller of an authenticated route; 401 when called outside `authenticate(JWT_AUTH)`. */
fun ApplicationCall.caller(): Caller = callerOrNull() ?: throw ApplicationError.Unauthorized()

/** Logs refused tokens and failed authentications; never the token itself or a claim value. */
private val authLog = LoggerFactory.getLogger("com.carevoice.documentation.api.Authentication")

/**
 * JWT bearer authentication (HS256, issuer + audience + required claims), installed under the name [JWT_AUTH].
 * Invalid, expired or incomplete tokens all get the same 401 problem with `WWW-Authenticate: Bearer`; the reason is
 * never revealed to the client. A token must carry `sub`, tenant, ward and roles; role names that are not known are
 * dropped.
 *
 * [clock] makes expiry checks deterministic in tests.
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

                // The text of a string claim, or null when it is absent or blank.
                fun textClaim(claim: String): String? = payload.getClaim(claim).asString()?.takeIf { it.isNotBlank() }

                // Read the required claims: subject (the user), tenant and ward.
                val subject = payload.subject?.takeIf { it.isNotBlank() } ?: return@validate missing("sub")
                val tenant = textClaim(JwtClaims.TENANT) ?: return@validate missing(JwtClaims.TENANT)
                val ward = textClaim(JwtClaims.WARD) ?: return@validate missing(JwtClaims.WARD)
                // Roles are optional. Unknown role names are dropped.
                val roleNames = payload.getClaim(JwtClaims.ROLES).asList(String::class.java)
                val roles = roleNames?.mapNotNull(Role::parse)?.toSet().orEmpty()
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
                    "path" to call.loggedPath(),
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
            // Leeway is the clock difference allowed when checking `exp`, `nbf` and `iat`; 0 seconds means none.
            .acceptLeeway(0)
    return (verification as JWTVerifier.BaseVerification).build(clock)
}
