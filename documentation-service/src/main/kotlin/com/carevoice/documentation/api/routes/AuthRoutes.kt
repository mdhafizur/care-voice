package com.carevoice.documentation.api.routes

import com.carevoice.documentation.api.Dependencies
import com.carevoice.documentation.api.TokenRequest
import com.carevoice.documentation.api.TokenResponse
import com.carevoice.documentation.api.receiveJson
import com.carevoice.documentation.api.respondJson
import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.application.RateLimitDecision
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.time.Duration.Companion.minutes

/**
 * `POST /v1/auth/token`. Rate-limited per client IP (10/min, docs/CONTRACTS.md, section 11) BEFORE the body is read
 * or a password is hashed: the limit exists to make password guessing expensive, so it must not cost a bcrypt first.
 *
 * Behind a load balancer `origin.remoteHost` is the address Ktor resolved; production deployments install
 * ForwardedHeaders at the edge they trust (not here, to avoid trusting spoofable headers by default).
 */
fun Route.authRoutes(deps: Dependencies) {
    post("/v1/auth/token") {
        val ip = call.request.origin.remoteHost
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
