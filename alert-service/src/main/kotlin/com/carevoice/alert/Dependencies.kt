package com.carevoice.alert

import com.carevoice.alert.api.AlertApi
import com.carevoice.alert.api.JwtSettings
import com.carevoice.alert.application.AlertRepository
import com.carevoice.alert.application.CheckReadiness
import com.carevoice.alert.application.DependencyHealthCheck
import com.carevoice.alert.application.Embedder
import com.carevoice.alert.application.GuidanceWriter
import com.carevoice.alert.application.IdGenerator
import com.carevoice.alert.application.ProcessObservationEvent
import com.carevoice.alert.application.ProcessedEventRepository
import com.carevoice.alert.application.ProtocolChunkRepository
import com.carevoice.alert.config.AppConfig
import com.carevoice.alert.domain.AlertEvaluator
import com.carevoice.alert.domain.AlertRule
import com.carevoice.alert.infrastructure.guidance.HashingEmbedder
import com.carevoice.alert.observability.MicrometerAlertMetrics
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import kotlin.time.Duration

/**
 * Everything [module] needs, passed in explicitly (manual constructor injection, no DI framework). Tests build it from
 * in-memory fakes, a fixed [clock] and deterministic [ids]; production builds it from the Mongo adapters in [main].
 *
 * @property healthChecks what `/readyz` checks (Mongo primary, RabbitMQ consumer).
 * @property protocolChunks the guidance knowledge base; null = none (every alert gets NO_MATCHING_PROTOCOL).
 * @property guidanceWriter the LLM writer for guidance; null = extractive guidance only.
 * @property requestTimeout upper bound for one HTTP request (`REQUEST_TIMEOUT_MS`); past it the call is cancelled, 503.
 * @property readinessPropagationDelay how long the pod keeps serving with readiness DOWN before it stops, so the load
 *   balancer has observed the 503 and routes no new requests to it (docs/CONTRACTS.md, section 9b).
 */
class Dependencies(
    val jwt: JwtSettings,
    val rateLimitPerMinute: Int,
    val meterRegistry: MeterRegistry,
    val scrapeMetrics: () -> String,
    val alerts: AlertRepository,
    val processedEvents: ProcessedEventRepository,
    val healthChecks: List<DependencyHealthCheck>,
    val clock: Clock = Clock.systemUTC(),
    val ids: IdGenerator = IdGenerator.RANDOM_UUID,
    val evaluator: AlertRule = AlertEvaluator.default(),
    val eventConcurrency: Int = AlertApi.DEFAULT_EVENT_CONCURRENCY,
    val processObservationEvent: ProcessObservationEvent =
        ProcessObservationEvent(evaluator, alerts, processedEvents, clock, ids, MicrometerAlertMetrics(meterRegistry)),
    val embedder: Embedder = HashingEmbedder(),
    val protocolChunks: ProtocolChunkRepository? = null,
    val guidanceWriter: GuidanceWriter? = null,
    val readinessPropagationDelay: Duration = Duration.ZERO,
    val requestTimeout: Duration = AppConfig.DEFAULT_REQUEST_TIMEOUT,
    /** Called once when the server begins to stop, after readiness flipped and before requests are drained. */
    val beforeDrain: suspend () -> Unit = {},
) {
    val readiness: CheckReadiness = CheckReadiness(healthChecks)
}
