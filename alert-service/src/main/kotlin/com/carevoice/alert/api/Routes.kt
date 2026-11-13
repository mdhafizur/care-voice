package com.carevoice.alert.api

import com.carevoice.alert.application.AcknowledgeAlert
import com.carevoice.alert.application.CheckReadiness
import com.carevoice.alert.application.EventParser
import com.carevoice.alert.application.FieldViolation
import com.carevoice.alert.application.GetAlert
import com.carevoice.alert.application.GetAlertGuidance
import com.carevoice.alert.application.ListAlerts
import com.carevoice.alert.application.ProcessObservationEvent
import com.carevoice.alert.domain.AlertStatus
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.coroutines.sync.Semaphore

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

/**
 * `POST /v1/events/observations`.
 *
 * Backpressure: a `Semaphore(64)` bounds concurrent processing per instance, and `tryAcquire` NEVER waits — when all
 * slots are busy the call is answered immediately with 503 + `Retry-After: 1`. Queueing instead would let latency and
 * memory grow without bound under a burst (and hold the caller's connection while it waits); the outbox relay treats
 * 503 as retryable and backs off, so rejecting is lossless. Deliberately per instance: it protects this pod's own
 * Mongo pool and heap. Not behind the per-user rate limit: the only caller is the relay, whose throughput is governed
 * by this semaphore and its own backoff.
 */
private fun Route.eventRoutes(api: AlertApi) {
    val slots = Semaphore(api.eventConcurrency)
    post {
        // Step 1: read the request body.
        val body = call.receiveJsonText()
        // Step 2: take a free processing slot, or answer 503 at once when all are busy (see above).
        if (!slots.tryAcquire()) throw ApiException.Overloaded()
        try {
            // Step 3: parse and process the event; `finally` always gives the slot back.
            val event = EventParser.parse(body)
            val result = api.processObservationEvent.execute(event)
            // Step 4: a duplicate event answers 200, a new one 202 (accepted).
            val status = if (result.duplicate) HttpStatusCode.OK else HttpStatusCode.Accepted
            call.respondText(
                ApiJson.encodeToString(EventAcceptedDto.serializer(), AlertDtoMapper.toDto(result)),
                ContentType.Application.Json,
                status,
            )
        } finally {
            slots.release()
        }
    }
}

/**
 * `/v1/alerts`: list, get one, get guidance, and acknowledge. Every lookup stays inside the caller's ward.
 * The list checks `status` and `limit` and answers 400 with all invalid fields at once.
 * List, get and acknowledge send an ETag (a version tag of the body), so clients can check for changes cheaply.
 */
private fun Route.alertRoutes(api: AlertApi) {
    // List alerts.
    get {
        // Step 1: check `status` and `limit`, and collect every problem, so one 400 lists them all.
        val query = call.request.queryParameters
        val violations = mutableListOf<FieldViolation>()
        val status =
            query["status"]?.let { raw ->
                AlertStatus.entries.firstOrNull { it.name == raw }
                    ?: null.also { violations += FieldViolation("status", "must be one of ${AlertStatus.entries}") }
            }
        val limit =
            query["limit"]?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in ListAlerts.MIN_LIMIT..ListAlerts.MAX_LIMIT }
                    ?: null.also { violations += FieldViolation("limit", LIMIT_MESSAGE) }
            } ?: ListAlerts.DEFAULT_LIMIT
        if (violations.isNotEmpty()) {
            throw ApiException.BadRequest("One or more request parameters are invalid.", violations)
        }

        // Step 2: load one page of alerts from the caller's ward.
        val page = api.listAlerts.execute(call.caller.scope, status, limit, query["cursor"])
        val json = ApiJson.encodeToString(AlertPageDto.serializer(), AlertDtoMapper.toDto(page))
        // Step 3: add the total count and the paging links (`Link` header), then send the page with an ETag.
        call.response.header("X-Total-Count", page.totalCount.toString())
        call.response.header(HttpHeaders.Link, linkHeader(status, limit, page.nextCursor, page.prevCursor))
        call.respondJsonWithETag(json, weakETagOf(json))
    }

    // Get one alert.
    get("/{alertId}") {
        val alert = api.getAlert.execute(call.caller.scope, call.alertId())
        val json = ApiJson.encodeToString(AlertDto.serializer(), AlertDtoMapper.toDto(alert))
        call.respondJsonWithETag(json, versionETag(alert.version))
    }

    // Advisory protocol guidance (RAG, ADR-0023). Not cached with an ETag: generated text may differ between calls.
    get("/{alertId}/guidance") {
        val guidance = api.getAlertGuidance.execute(call.caller.scope, call.alertId())
        call.respondText(
            ApiJson.encodeToString(AlertGuidanceDto.serializer(), AlertDtoMapper.toDto(guidance)),
            ContentType.Application.Json,
        )
    }

    // Acknowledge an alert. `If-Match` must name the version the client saw.
    patch("/{alertId}/acknowledge") {
        val alert =
            api.acknowledgeAlert.execute(
                scope = call.caller.scope,
                alertId = call.alertId(),
                precondition = parseIfMatch(call.request.header(HttpHeaders.IfMatch)),
                acknowledgedBy = call.caller.username,
            )
        val json = ApiJson.encodeToString(AlertDto.serializer(), AlertDtoMapper.toDto(alert))
        call.respondJsonWithETag(json, versionETag(alert.version), cacheable = false)
    }
}

/** An id that cannot exist is simply not found; no separate 400, so the response reveals nothing about id formats. */
private fun ApplicationCall.alertId(): String = parameters["alertId"].orEmpty()

/** RFC 8288 `Link` with `first`, `next` (when more) and `prev` (when not on the first page). */
internal fun linkHeader(status: AlertStatus?, limit: Int, nextCursor: String?, prevCursor: String?): String {
    // Builds one page link; null means the first page, and the cursor is URL-encoded.
    fun url(cursor: String?): String = buildString {
        append("/v1/alerts?limit=").append(limit)
        status?.let { append("&status=").append(it.name) }
        cursor?.let { append("&cursor=").append(it.encodeURLParameter()) }
    }
    return buildList {
        add("<${url(null)}>; rel=\"first\"")
        nextCursor?.let { add("<${url(it)}>; rel=\"next\"") }
        prevCursor?.let { add("<${url(it)}>; rel=\"prev\"") }
    }.joinToString(", ")
}

/** Health, metrics and API docs: no authentication (docs/CONTRACTS.md, section 6). */
private fun Route.operationsRoutes(api: AlertApi) {
    // Liveness: the process is running. It does not check any dependency.
    get("/livez") {
        call.respondText(
            ApiJson.encodeToString(HealthDto.serializer(), HealthDto("UP", emptyMap())),
            ContentType.Application.Json,
        )
    }
    // Readiness: can this pod take traffic now? Not ready gives 503 and a `Retry-After`.
    get("/readyz") {
        val report = api.readiness.check()
        val status = if (report.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        if (!report.ready) call.response.header(HttpHeaders.RetryAfter, "5")
        call.respondText(
            ApiJson.encodeToString(HealthDto.serializer(), AlertDtoMapper.toDto(report)),
            ContentType.Application.Json,
            status,
        )
    }
    get("/metrics") {
        call.respondText(api.scrapeMetrics(), PROMETHEUS_CONTENT_TYPE)
    }
    // API description (OpenAPI) and the Swagger UI page that shows it.
    get("/openapi.yaml") {
        val spec =
            AlertApi::class.java.classLoader
                .getResource(AlertApi.OPENAPI_RESOURCE)
                ?.readText()
                ?: error("OpenAPI resource ${AlertApi.OPENAPI_RESOURCE} is missing from the classpath")
        // Any cache (`public`) may keep the spec for 300 seconds (5 minutes).
        call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
        call.respondText(spec, ContentType.parse("application/yaml"))
    }
    swaggerUI(path = "docs", swaggerFile = AlertApi.OPENAPI_RESOURCE)
}

private const val LIMIT_MESSAGE = "must be an integer between ${ListAlerts.MIN_LIMIT} and ${ListAlerts.MAX_LIMIT}"

/**
 * Prometheus text exposition format, version 0.0.4:
 * https://prometheus.io/docs/instrumenting/exposition_formats/
 */
private val PROMETHEUS_CONTENT_TYPE = ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
