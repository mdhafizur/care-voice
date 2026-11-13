package com.carevoice.alert.api

import com.carevoice.alert.logging.event
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.event.Level

private val RouteTemplateKey = AttributeKey<String>("RouteTemplate")
private val CallStartKey = AttributeKey<Long>("CallStartNanos")

/**
 * Remembers the matched route template (`/v1/alerts/{alertId}`) so the access log groups by endpoint, never by the
 * concrete id (unbounded cardinality, and ids in logs are not needed for operations).
 */
internal fun Application.recordRouteTemplates() {
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

/** Probes and the metrics scrape: they run every few seconds per pod, so they get no access-log line. */
private val UNLOGGED_PATHS = setOf("/livez", "/readyz", "/metrics")

/**
 * One access-log line per request (`type=request`): method, path template, status, duration. Logged after the
 * response was sent, with the request id restored explicitly because this hook runs outside the call's MDC scope.
 */
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
