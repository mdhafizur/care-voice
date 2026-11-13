package com.carevoice.alert.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Health, metrics and API docs: no authentication (docs/CONTRACTS.md, section 6). */
internal fun Route.operationsRoutes(api: AlertApi) {
    // Liveness: the process is running. It does not check any dependency.
    get("/livez") {
        call.respondText(
            ApiJson.encodeToString(HealthDto.serializer(), HealthDto("UP", emptyMap())),
            ContentType.Application.Json,
        )
    }
    // Readiness: can this pod take traffic now? Not ready gives 503 and a `Retry-After`.
    get("/readyz") {
        // Step 1: run every health check (MongoDB, RabbitMQ, ...).
        val report = api.readiness.check()
        // Step 2: 200 when ready, 503 when not; a 503 also says when to ask again.
        val status = if (report.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        if (!report.ready) call.response.header(HttpHeaders.RetryAfter, "5")
        // Step 3: one status per dependency in the body.
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
        // Read the spec file packed into the jar; a missing file is a build mistake, so fail loudly.
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

/**
 * Prometheus text exposition format, version 0.0.4:
 * https://prometheus.io/docs/instrumenting/exposition_formats/
 */
private val PROMETHEUS_CONTENT_TYPE = ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
