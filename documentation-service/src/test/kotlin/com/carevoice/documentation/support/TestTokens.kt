package com.carevoice.documentation.support

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.carevoice.documentation.application.JwtClaims
import com.carevoice.documentation.config.JwtConfig
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/** Mints JWTs for tests: valid ones exactly like the issuer, and every kind of invalid one the contracts need. */
object TestTokens {
    const val SECRET = "documentation-service-test-secret-0123456789"
    const val OTHER_SECRET = "a-completely-different-secret-0123456789abc"
    const val ISSUER = "carevoice-auth"
    const val AUDIENCE = "carevoice"
    const val MALFORMED = "not.a.jwt"

    val jwtConfig: JwtConfig = JwtConfig(SECRET.toByteArray(), ISSUER, AUDIENCE, 15.minutes)

    fun token(
        clock: Clock,
        username: String,
        tenant: String,
        ward: String,
        roles: List<String>,
        issuer: String = ISSUER,
        audience: String = AUDIENCE,
        secret: String = SECRET,
        expiresIn: Duration = Duration.ofMinutes(15),
    ): String {
        val now = clock.instant()
        return JWT
            .create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(username)
            .withClaim(JwtClaims.TENANT, tenant)
            .withClaim(JwtClaims.WARD, ward)
            .withClaim(JwtClaims.ROLES, roles)
            .withIssuedAt(Date.from(now.minus(Duration.ofMinutes(30))))
            .withExpiresAt(Date.from(now.plus(expiresIn)))
            .withJWTId(UUID.randomUUID().toString())
            .sign(Algorithm.HMAC256(secret))
    }
}
