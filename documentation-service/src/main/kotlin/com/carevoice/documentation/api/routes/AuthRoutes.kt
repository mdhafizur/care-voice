package com.carevoice.documentation.api.routes

import com.carevoice.documentation.api.Dependencies
import com.carevoice.documentation.api.TokenRequest
import com.carevoice.documentation.api.TokenResponse
import com.carevoice.documentation.api.clientAddress
import com.carevoice.documentation.api.receiveJson
import com.carevoice.documentation.api.respondJson
import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.application.RateLimitDecision
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.time.Duration.Companion.minutes

/**
 * `POST /v1/auth/token`. Rate-limited per client IP (10/min, docs/CONTRACTS.md, section 11) BEFORE the body is read
 * or a password is hashed: the limit exists to make password guessing expensive, so it must not cost a bcrypt first.
 *
 * The IP is the real client's, not Traefik's: [clientAddress] believes `X-Forwarded-For` only when the connection
 * comes from a proxy in `TRUSTED_PROXIES`, so every user gets their own limit and nobody can fake a new address per
 * attempt.
 */
fun Route.authRoutes(deps: Dependencies) {
    post("/v1/auth/token") {
        val ip = call.clientAddress(deps.http.trustedProxies)
        when (
            val decision = deps.rateLimiter.tryAcquire(
                LOGIN_SCOPE,
                ip,
                deps.http.loginRateLimitPerMinute,
                1.minutes,
            )
        ) {
            is RateLimitDecision.Rejected -> throw ApplicationError.TooManyRequests(decision.retryAfter)
            is RateLimitDecision.Allowed -> Unit
        }
        val request = call.receiveJson(TokenRequest.serializer(), deps.http.maxBodyBytes)
        val token = deps.authenticateUser.execute(request.username, request.password)
        call.respondJson(
            TokenResponse.serializer(),
            TokenResponse(token.accessToken, "Bearer", token.expiresIn.inWholeSeconds),
        )
    }
}

const val LOGIN_SCOPE = "login-ip"
