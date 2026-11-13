package com.carevoice.alert.api

import com.carevoice.alert.logging.event
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.opentelemetry.api.trace.Span
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.event.Level
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Valid incoming request ids (docs/CONTRACTS.md, section 6): 1 to 64 characters, each a letter, a digit, `.`, `_`,
 * `:` or `-`. Anything else is replaced by a generated one.
 */
val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,64}")

val PER_USER_RATE_LIMIT = RateLimitName("per-user")

/**
 * Installs the cross-cutting HTTP plugins. Order matters only where noted; every plugin is configured explicitly so the
 * behaviour does not depend on library defaults.
 */
fun Application.installHttpPlugins(jwt: JwtSettings, rateLimitPerMinute: Int, registry: MeterRegistry) {
    // Request id: take X-Request-Id from the caller or make a new one, and send it back in the answer.
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        // An invalid incoming id is not rejected; it is ignored and the generator supplies a new one.
        verify { REQUEST_ID_PATTERN.matches(it) }
        replyToHeader(HttpHeaders.XRequestId)
    }
    // Used only for its MDC wrapping of the whole pipeline (requestId, traceId, spanId); the access log is AccessLog.
    install(CallLogging) {
        level = Level.DEBUG
        callIdMdc("requestId")
        mdc("traceId") { currentSpanContext()?.first }
        mdc("spanId") { currentSpanContext()?.second }
        filter { false }
    }
    // Access log, safe default headers, and no caching of answers unless a route sets Cache-Control.
    install(AccessLog)
    install(DefaultHeaders) {
        // nosniff: the browser must not guess a content type other than the one sent (docs/CONTRACTS.md, section 11).
        header("X-Content-Type-Options", "nosniff")
        // no-referrer: the browser sends no `Referer` header when it leaves a page.
        header("Referrer-Policy", "no-referrer")
    }
    install(DefaultNoStore)
    // Error answers, the JWT login check and a rate limit per user.
    installStatusPages()
    install(Authentication) { configureJwt(jwt) }
    install(RateLimit) {
        register(PER_USER_RATE_LIMIT) {
            rateLimiter(limit = rateLimitPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.rateLimitKey() }
        }
    }
    // Metrics: request time histograms plus JVM and process metrics.
    install(MicrometerMetrics) {
        this.registry = registry
        // Buckets of docs/CONTRACTS.md, section 10: SLO bounds at 100 ms, 300 ms (the dashboard's "% under 300 ms")
        // and 1 s; durations between 5 ms and 10 s are expected.
        distributionStatisticConfig =
            DistributionStatisticConfig
                .builder()
                .percentilesHistogram(true)
                .serviceLevelObjectives(
                    Duration.ofMillis(100).toNanos().toDouble(),
                    Duration.ofMillis(300).toNanos().toDouble(),
                    Duration.ofSeconds(1).toNanos().toDouble(),
                ).minimumExpectedValue(Duration.ofMillis(5).toNanos().toDouble())
                .maximumExpectedValue(Duration.ofSeconds(10).toNanos().toDouble())
                .build()
        meterBinders =
            listOf(
                JvmMemoryMetrics(),
                JvmGcMetrics(),
                JvmThreadMetrics(),
                ClassLoaderMetrics(),
                ProcessorMetrics(),
                UptimeMetrics(),
            )
    }
    recordRouteTemplates()
}

/** Per authenticated user; unauthenticated calls never reach a rate-limited route, the IP is a defensive fallback. */
private fun ApplicationCall.rateLimitKey(): String =
    runCatching { caller.username }.getOrNull()?.let { "user:$it" } ?: "ip:${request.origin.remoteHost}"

/** (traceId, spanId) of the span the OpenTelemetry agent opened for this request, when an agent is attached. */
private fun currentSpanContext(): Pair<String, String>? {
    val context = Span.current().spanContext
    return if (context.isValid) context.traceId to context.spanId else null
}

private val RouteTemplateKey = AttributeKey<String>("RouteTemplate")
private val CallStartKey = AttributeKey<Long>("CallStartNanos")

/**
 * Remembers the matched route template (`/v1/alerts/{alertId}`) so the access log groups by endpoint, never by the
 * concrete id (unbounded cardinality, and ids in logs are not needed for operations).
 */
private fun Application.recordRouteTemplates() {
    monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
        // Ktor's route text has selector parts such as `/(method:GET)`; the regex removes every `/(...)` part.
        val template =
            call.route
                .toString()
                .replace(Regex("/\\([^)]*\\)"), "")
                .ifEmpty { "/" }
        call.attributes.put(RouteTemplateKey, template)
    }
}

/**
 * One access-log line per request (`type=request`): method, path template, status, duration. Logged after the
 * response was sent, with the request id restored explicitly because this hook runs outside the call's MDC scope.
 */
private val UNLOGGED_PATHS = setOf("/livez", "/readyz", "/metrics")

val AccessLog =
    createApplicationPlugin("AccessLog") {
        val log = LoggerFactory.getLogger("com.carevoice.alert.access")
        onCall { call -> call.attributes.put(CallStartKey, System.nanoTime()) }
        on(ResponseSent) { call ->
            val start = call.attributes.getOrNull(CallStartKey) ?: return@on
            val durationMs = (System.nanoTime() - start) / 1_000_000
            val template = call.attributes.getOrNull(RouteTemplateKey) ?: "unmatched"
            // Probes and scrapes run every few seconds per pod; readiness changes are logged by CheckReadiness instead.
            if (call.request.path() in UNLOGGED_PATHS) return@on
            val status = call.response.status()?.value ?: 0
            val previous = MDC.getCopyOfContextMap()
            try {
                MDC.put("type", "request")
                call.callId?.let { MDC.put("requestId", it) }
                val method = call.request.httpMethod.value
                log.event(
                    if (status >= 500) Level.ERROR else Level.INFO,
                    null,
                    "$method $template $status ${durationMs}ms",
                    "method" to method,
                    "route" to template,
                    "status" to status,
                    "durationMs" to durationMs,
                )
            } finally {
                if (previous == null) MDC.clear() else MDC.setContextMap(previous)
            }
        }
    }

/**
 * `Cache-Control: no-store` on every response that did not choose a caching policy itself (docs/CONTRACTS.md,
 * section 11): alert data must not end up in shared or disk caches.
 */
val DefaultNoStore =
    createApplicationPlugin("DefaultNoStore") {
        onCallRespond { call, _ ->
            if (call.response.headers[HttpHeaders.CacheControl] == null) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
            }
        }
    }
