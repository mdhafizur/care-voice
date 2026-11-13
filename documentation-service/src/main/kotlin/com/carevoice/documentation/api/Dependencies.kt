package com.carevoice.documentation.api

import com.carevoice.documentation.application.AuthenticateUser
import com.carevoice.documentation.application.ConfirmObservation
import com.carevoice.documentation.application.DistributedRateLimiter
import com.carevoice.documentation.application.GetResident
import com.carevoice.documentation.application.GetTranscript
import com.carevoice.documentation.application.ListResidentObservations
import com.carevoice.documentation.application.ObservationStream
import com.carevoice.documentation.application.QueryObservations
import com.carevoice.documentation.application.Readiness
import com.carevoice.documentation.application.SubmitTranscript
import com.carevoice.documentation.config.IpNetwork
import com.carevoice.documentation.config.JwtConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** HTTP-level limits (docs/CONTRACTS.md, sections 6, 7 and 11). */
data class HttpSettings(
    /** Requests per signed-in user per minute (`RATE_LIMIT_PER_MINUTE`, default 120). */
    val rateLimitPerMinute: Int = 120,
    /** Login attempts per client IP per minute; fixed at 10 (docs/CONTRACTS.md, section 4). */
    val loginRateLimitPerMinute: Int = 10,
    /** Largest accepted request body, 64 KiB; a bigger one gets 413 (docs/CONTRACTS.md, section 6). */
    val maxBodyBytes: Long = 64L * 1024,
    /** How often the SSE stream sends a heartbeat comment, so proxies do not close an idle connection. */
    val sseHeartbeat: Duration = 15.seconds,
    /** Time limit for one request (`REQUEST_TIMEOUT_MS`, default 30 s); after it the answer is 503. */
    val requestTimeout: Duration = 30.seconds,
    /** Proxies whose `X-Forwarded-For` is believed (`TRUSTED_PROXIES`); empty = the header is ignored. */
    val trustedProxies: List<IpNetwork> = emptyList(),
)

/**
 * Everything the HTTP module needs, passed in by constructor injection (no DI framework, no service locator).
 *
 * Only application-layer types and config: the API never sees an adapter (architecture rule), so tests can build
 * this with fakes for every port and run the real routes in `testApplication { application { module(deps) } }`.
 */
class Dependencies(
    val authenticateUser: AuthenticateUser,
    val getResident: GetResident,
    val submitTranscript: SubmitTranscript,
    val getTranscript: GetTranscript,
    val listResidentObservations: ListResidentObservations,
    val queryObservations: QueryObservations,
    val confirmObservation: ConfirmObservation,
    val observationStream: ObservationStream,
    val readiness: Readiness,
    val rateLimiter: DistributedRateLimiter,
    val jwt: JwtConfig,
    val meterRegistry: PrometheusMeterRegistry,
    val clock: Clock = Clock.systemUTC(),
    val http: HttpSettings = HttpSettings(),
)
