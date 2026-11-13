package com.carevoice.alert.api

import com.carevoice.alert.application.AcknowledgeAlert
import com.carevoice.alert.application.CheckReadiness
import com.carevoice.alert.application.GetAlert
import com.carevoice.alert.application.GetAlertGuidance
import com.carevoice.alert.application.ListAlerts
import com.carevoice.alert.application.ProcessObservationEvent
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route

/** The use cases the HTTP API drives; the API never touches infrastructure directly. */
class AlertApi(
    val processObservationEvent: ProcessObservationEvent,
    val listAlerts: ListAlerts,
    val getAlert: GetAlert,
    val acknowledgeAlert: AcknowledgeAlert,
    val getAlertGuidance: GetAlertGuidance,
    val readiness: CheckReadiness,
    /** Renders the Prometheus scrape; a function so the API layer does not depend on a registry implementation. */
    val scrapeMetrics: () -> String,
    /** Concurrent event-processing slots of this instance (docs/CONTRACTS.md, section 9). */
    val eventConcurrency: Int = DEFAULT_EVENT_CONCURRENCY,
) {
    companion object {
        /** Event-processing slots per instance: the `Semaphore(64)` of docs/CONTRACTS.md, section 9. */
        const val DEFAULT_EVENT_CONCURRENCY = 64
        const val OPENAPI_RESOURCE = "openapi/alert-service.yaml"
    }
}

/**
 * Registers all routes. Health, metrics and docs need no login.
 * Events need the SERVICE role; the alert endpoints need WARD_LEAD and are rate-limited per user.
 */
fun Route.alertServiceRoutes(api: AlertApi) {
    operationsRoutes(api)
    authenticated {
        roleRoute("/v1/events/observations", Role.SERVICE) { eventRoutes(api) }
        roleRoute("/v1/alerts", Role.WARD_LEAD) {
            rateLimit(PER_USER_RATE_LIMIT) { alertRoutes(api) }
        }
    }
}
