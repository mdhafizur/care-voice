package com.carevoice.documentation.api

import com.carevoice.documentation.api.plugins.AuthenticatedMdc
import com.carevoice.documentation.api.plugins.JWT_AUTH
import com.carevoice.documentation.api.plugins.RoleGuard
import com.carevoice.documentation.api.plugins.UserRateLimit
import com.carevoice.documentation.api.plugins.installCorrelationAndAccessLog
import com.carevoice.documentation.api.plugins.installJwtAuthentication
import com.carevoice.documentation.api.plugins.installMetrics
import com.carevoice.documentation.api.plugins.installRequestDeadline
import com.carevoice.documentation.api.plugins.installSecurityHeaders
import com.carevoice.documentation.api.routes.OBSERVATION_STREAM_PATH
import com.carevoice.documentation.api.routes.authRoutes
import com.carevoice.documentation.api.routes.observationRoutes
import com.carevoice.documentation.api.routes.operationalRoutes
import com.carevoice.documentation.api.routes.residentRoutes
import com.carevoice.documentation.api.routes.streamRoutes
import com.carevoice.documentation.application.Role
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE

/**
 * The complete HTTP application, built only from [Dependencies]: `main` passes real adapters, tests pass fakes.
 *
 * Plugin order matters: correlation first (every later log line has a requestId), then metrics, security headers,
 * problem details, the request deadline (so a timed-out request still gets a problem response and an access-log
 * line), authentication. Request bodies are read by the handlers through `receiveJson`, which enforces
 * 415/413/400 itself, so no ContentNegotiation plugin is installed and no body is parsed implicitly.
 */
fun Application.httpModule(deps: Dependencies) {
    installCorrelationAndAccessLog()
    installMetrics(deps.meterRegistry)
    installSecurityHeaders()
    installProblemDetails()
    installRequestDeadline(deps.http.requestTimeout, exemptPaths = setOf(OBSERVATION_STREAM_PATH))
    installJwtAuthentication(deps.jwt, deps.clock)
    install(SSE)

    routing {
        operationalRoutes(deps)
        authRoutes(deps)
        authenticate(JWT_AUTH) {
            install(AuthenticatedMdc)
            install(RoleGuard) { allowed = Role.CARE_STAFF }
            install(UserRateLimit) {
                limiter = deps.rateLimiter
                perMinute = deps.http.rateLimitPerMinute
            }
            residentRoutes(deps)
            observationRoutes(deps)
            streamRoutes(deps)
        }
    }
}
