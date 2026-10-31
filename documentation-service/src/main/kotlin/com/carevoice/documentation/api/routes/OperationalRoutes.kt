package com.carevoice.documentation.api.routes

import com.carevoice.documentation.api.Dependencies
import com.carevoice.documentation.api.HealthResponse
import com.carevoice.documentation.api.respondJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Unauthenticated operational endpoints: health, metrics, the OpenAPI spec and Swagger UI. */
fun Route.operationalRoutes(deps: Dependencies) {
    get("/livez") {
        call.respondJson(HealthResponse.serializer(), HealthResponse("UP"))
    }

    // 503 while a critical dependency is down OR while shutting down (readiness flips first on SIGTERM). Redis and
    // RabbitMQ only show up as DEGRADED in the body: the service still works without them.
    get("/readyz") {
        val report = deps.readiness.check()
        val body = HealthResponse(report.overall.name, report.checks.associate { it.name to it.status.name })
        if (!report.ready) call.response.header(HttpHeaders.RetryAfter, "5")
        call.respondJson(
            HealthResponse.serializer(),
            body,
            if (report.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        )
    }

    get("/metrics") {
        call.respondText(deps.meterRegistry.scrape(), ContentType.parse(PROMETHEUS_CONTENT_TYPE))
    }

    val spec: String by lazy {
        requireNotNull(Dependencies::class.java.classLoader.getResource(OPENAPI_RESOURCE)) {
            "$OPENAPI_RESOURCE missing"
        }.readText()
    }
    get("/openapi.yaml") {
        call.respondText(spec, ContentType.parse("application/yaml"))
    }

    swaggerUI(path = "docs", swaggerFile = OPENAPI_RESOURCE)
}

const val OPENAPI_RESOURCE = "openapi/documentation-service.yaml"
const val PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"
