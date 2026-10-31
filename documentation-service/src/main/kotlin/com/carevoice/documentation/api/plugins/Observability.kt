package com.carevoice.documentation.api.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.event.Level
import java.time.Duration
import java.util.UUID

/** `X-Request-Id` values accepted from clients (docs/CONTRACTS.md, section 6); anything else is replaced. */
val REQUEST_ID_FORMAT = Regex("^[A-Za-z0-9._:-]{1,64}$")

const val MDC_REQUEST_ID = "requestId"

/**
 * Correlation: every request gets an `X-Request-Id` (the client's when valid, else a new UUID), echoed in the
 * response and put into the log MDC as `requestId` for the whole call. CallLogging wraps the pipeline in an MDC
 * context, so the id survives coroutine thread switches.
 *
 * Access log: exactly one line per request with method, path, status and duration, written by the logger
 * `io.ktor.server.Application`. Never the query string or body: both could carry personal data. (`type` is not put
 * into the MDC: it would duplicate the structured `type` field that business and database log lines carry.)
 */
fun Application.installCorrelationAndAccessLog() {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        // An invalid client value is ignored (verify = false), so the generator supplies a fresh id instead.
        verify { REQUEST_ID_FORMAT.matches(it) }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc(MDC_REQUEST_ID)
        disableDefaultColors()
        filter { call -> call.request.path() !in setOf("/livez", "/readyz", "/metrics") }
        format { call ->
            val status = call.response.status()?.value ?: 0
            "${call.request.httpMethod.value} ${call.request.path()} -> $status in ${call.processingTimeMillis()} ms"
        }
    }
}

/**
 * HTTP server metrics with server-side percentile histograms (docs/CONTRACTS.md, section 14): histograms aggregate
 * across pods (client-side percentiles cannot), and the SLO buckets 100 ms / 300 ms / 1 s make "share of requests
 * under 300 ms" an exact counter ratio. Plus JVM binders (memory, GC, threads, class loading, CPU, uptime).
 */
fun Application.installMetrics(registry: PrometheusMeterRegistry) {
    install(MicrometerMetrics) {
        this.registry = registry
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
}
