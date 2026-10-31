package com.carevoice.alert.support

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.time.Instant
import java.util.Date
import java.util.UUID

/** JWT factory: HS256 tokens with any roles, tenant, ward, expiry, issuer or audience (CONTRACTS.md, section 5). */
object Tokens {
    fun token(
        roles: List<String> = listOf("WARD_LEAD"),
        subject: String? = TestFixtures.WARD_LEAD,
        tenant: String? = TestFixtures.TENANT,
        ward: String? = TestFixtures.WARD,
        issuer: String = TestFixtures.ISSUER,
        audience: String = TestFixtures.AUDIENCE,
        // java-jwt verifies expiry against the real clock, so expiry is relative to the wall clock, not the test clock.
        expiresAt: Instant = Instant.now().plusSeconds(300),
        secret: String = TestFixtures.JWT_SECRET,
    ): String {
        val builder =
            JWT
                .create()
                .withIssuer(issuer)
                .withAudience(audience)
                .withIssuedAt(Date.from(expiresAt.minusSeconds(600)))
                .withExpiresAt(Date.from(expiresAt))
                .withJWTId(UUID.randomUUID().toString())
                .withClaim("roles", roles)
        subject?.let { builder.withSubject(it) }
        tenant?.let { builder.withClaim("tenant", it) }
        ward?.let { builder.withClaim("ward", it) }
        return builder.sign(Algorithm.HMAC256(secret))
    }

    fun bearer(token: String): String = "Bearer $token"

    fun wardLead(ward: String = TestFixtures.WARD): String = bearer(token(roles = listOf("WARD_LEAD"), ward = ward))

    fun service(): String =
        bearer(token(roles = listOf("SERVICE"), subject = TestFixtures.SERVICE_SUBJECT, tenant = "*", ward = "*"))
}
