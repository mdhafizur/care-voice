package com.carevoice.alert

import com.carevoice.alert.api.AlertApi
import com.carevoice.alert.api.alertServiceRoutes
import com.carevoice.alert.api.installHttpPlugins
import com.carevoice.alert.api.installRequestDeadline
import com.carevoice.alert.application.AcknowledgeAlert
import com.carevoice.alert.application.GetAlert
import com.carevoice.alert.application.GetAlertGuidance
import com.carevoice.alert.application.ListAlerts
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Same logger name as in Application.kt, so the shutdown line keeps the logger name it always had. */
private val log = LoggerFactory.getLogger("com.carevoice.alert.Application")

/**
 * The HTTP application. Pure wiring: plugins, use cases built on the given ports, routes, and the shutdown order.
 *
 * Shutdown (SIGTERM → engine stop): readiness flips to DOWN first, the pod keeps serving for
 * [Dependencies.readinessPropagationDelay] so the load balancer stops routing to it, [Dependencies.beforeDrain] stops
 * the AMQP consumer after its in-flight messages, and only then does the engine drain in-flight HTTP requests.
 */
fun Application.module(deps: Dependencies) {
    // Step 1: install the HTTP plugins (request ids, login check, rate limit, metrics) and the request time limit.
    installHttpPlugins(deps.jwt, deps.rateLimitPerMinute, deps.meterRegistry)
    installRequestDeadline(deps.requestTimeout)
    // Step 2: build the API from its use cases and serve its routes.
    val api =
        AlertApi(
            processObservationEvent = deps.processObservationEvent,
            listAlerts = ListAlerts(deps.alerts),
            getAlert = GetAlert(deps.alerts),
            acknowledgeAlert = AcknowledgeAlert(deps.alerts, deps.clock),
            getAlertGuidance = GetAlertGuidance(deps.alerts, deps.embedder, deps.protocolChunks, deps.guidanceWriter),
            readiness = deps.readiness,
            scrapeMetrics = deps.scrapeMetrics,
            eventConcurrency = deps.eventConcurrency,
        )
    routing { alertServiceRoutes(api) }

    // Step 3: on shutdown, first report not ready and wait a moment, so new traffic goes to other pods.
    // Only then drain the work in progress. The flag makes sure this runs only once.
    val stopping = AtomicBoolean(false)
    monitor.subscribe(ApplicationStopPreparing) {
        if (stopping.compareAndSet(false, true)) {
            log.info("Shutdown started: readiness DOWN, draining")
            deps.readiness.markShuttingDown()
            runBlocking {
                delay(deps.readinessPropagationDelay)
                deps.beforeDrain()
            }
        }
    }
}
