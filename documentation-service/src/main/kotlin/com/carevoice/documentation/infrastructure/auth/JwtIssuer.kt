package com.carevoice.documentation.infrastructure.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.carevoice.documentation.application.IssuedToken
import com.carevoice.documentation.application.JwtClaims
import com.carevoice.documentation.application.Role
import com.carevoice.documentation.application.TokenIssuer
import com.carevoice.documentation.application.UserAccount
import com.carevoice.documentation.config.JwtConfig
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Adapter: issues HS256 access tokens with com.auth0 java-jwt (the library behind ktor-server-auth-jwt, so issuing and
 * verifying use the same implementation). Claims: iss, aud, sub, tenant, ward, roles, iat, exp, jti. Verification lives in
 * the HTTP layer (api/plugins/Authentication.kt), which only needs the shared [JwtConfig].
 */
class JwtIssuer(
    private val config: JwtConfig,
    private val clock: Clock,
    private val newTokenId: () -> UUID = UUID::randomUUID,
) : TokenIssuer {
    private val algorithm: Algorithm = Algorithm.HMAC256(config.secret)
    private val log = LoggerFactory.getLogger(JwtIssuer::class.java)

    /** Issues a signed access token for the user's own tenant, ward and role. */
    override fun issue(user: UserAccount): IssuedToken = IssuedToken(
        accessToken = sign(
            user.username,
            user.tenantId,
            user.wardId,
            listOf(user.role.name),
            config.accessTokenTtl,
        ),
        expiresIn = config.accessTokenTtl,
    )

    /** A `SERVICE` token for service-to-service calls (documentation-service → alert-service). */
    fun issueServiceToken(ttl: Duration = SERVICE_TOKEN_TTL): String =
        sign(SERVICE_SUBJECT, SERVICE_SCOPE, SERVICE_SCOPE, listOf(Role.SERVICE.name), ttl)

    /** Builds and signs an HS256 JWT with all claims, and logs its id (jti), never the token itself. */
    private fun sign(subject: String, tenant: String, ward: String, roles: List<String>, ttl: Duration): String {
        // Step 1: work out the token id (jti) and the issue and expiry times.
        val now = clock.instant()
        val tokenId = newTokenId().toString()
        val expiresAt = now.plus(ttl.toJavaDuration())
        // Step 2: log that a token was issued, at DEBUG.
        // The jti identifies the token in logs without revealing it; the token itself is never logged.
        log.event(
            Level.DEBUG,
            LogType.BUSINESS,
            "Token issued",
            "userId" to subject,
            "tokenId" to tokenId,
            "roles" to roles.joinToString(","),
            "expiresAt" to expiresAt.toString(),
        )
        // Step 3: build the token with all claims and sign it with the shared secret (HS256).
        return JWT
            .create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(subject)
            .withClaim(JwtClaims.TENANT, tenant)
            .withClaim(JwtClaims.WARD, ward)
            .withClaim(JwtClaims.ROLES, roles)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiresAt))
            .withJWTId(tokenId)
            .sign(algorithm)
    }

    companion object {
        const val SERVICE_SUBJECT = "documentation-service"

        /**
         * The `tenant` and `ward` claim of a SERVICE token. The token belongs to no single tenant or ward, but
         * alert-service rejects a token without these claims, so they carry this placeholder. alert-service's event
         * endpoint does not read them.
         */
        private const val SERVICE_SCOPE = "*"

        /** Lifetime of a SERVICE token: 5 minutes (docs/CONTRACTS.md, section 5). */
        val SERVICE_TOKEN_TTL: Duration = 5.minutes
    }
}

/**
 * Caches the 5-minute `SERVICE` token and refreshes it 30 s before expiry, so the outbox relay does not sign a token
 * per event, and never sends one that expires in flight. A [Mutex] makes concurrent publishers share one refresh.
 */
class ServiceTokenProvider(
    private val issuer: JwtIssuer,
    private val clock: Clock,
    private val ttl: Duration = JwtIssuer.SERVICE_TOKEN_TTL,
    private val refreshBefore: Duration = 30.seconds,
) {
    private val mutex = Mutex()
    private val log = LoggerFactory.getLogger(ServiceTokenProvider::class.java)
    private var cached: String? = null
    private var refreshAt: Instant = Instant.MIN

    /** Returns the cached service token, or issues a new one when its refresh time has come. */
    suspend fun token(): String = mutex.withLock {
        val now = clock.instant()
        val current = cached
        if (current != null && now.isBefore(refreshAt)) return@withLock current
        issuer.issueServiceToken(ttl).also {
            cached = it
            refreshAt = now.plus((ttl - refreshBefore).toJavaDuration())
            log.event(
                Level.DEBUG,
                LogType.BUSINESS,
                if (current == null) "Service token created" else "Service token refreshed",
                "refreshAt" to refreshAt.toString(),
            )
        }
    }
}
