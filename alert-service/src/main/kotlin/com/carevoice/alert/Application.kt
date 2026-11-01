package com.carevoice.alert

import com.carevoice.alert.api.AlertApi
import com.carevoice.alert.api.JwtSettings
import com.carevoice.alert.api.alertServiceRoutes
import com.carevoice.alert.api.installHttpPlugins
import com.carevoice.alert.api.installRequestDeadline
import com.carevoice.alert.application.AcknowledgeAlert
import com.carevoice.alert.application.AlertRepository
import com.carevoice.alert.application.CheckReadiness
import com.carevoice.alert.application.DependencyHealthCheck
import com.carevoice.alert.application.Embedder
import com.carevoice.alert.application.GetAlert
import com.carevoice.alert.application.GetAlertGuidance
import com.carevoice.alert.application.GuidanceWriter
import com.carevoice.alert.application.IdGenerator
import com.carevoice.alert.application.IngestProtocols
import com.carevoice.alert.application.ListAlerts
import com.carevoice.alert.application.ProcessObservationEvent
import com.carevoice.alert.application.ProcessedEventRepository
import com.carevoice.alert.application.ProtocolChunkRepository
import com.carevoice.alert.config.AppConfig
import com.carevoice.alert.config.ConfigException
import com.carevoice.alert.domain.AlertEvaluator
import com.carevoice.alert.domain.AlertRule
import com.carevoice.alert.infrastructure.amqp.ObservationEventConsumer
import com.carevoice.alert.infrastructure.guidance.ClasspathProtocolSource
import com.carevoice.alert.infrastructure.guidance.GuidanceHttpClient
import com.carevoice.alert.infrastructure.guidance.HashingEmbedder
import com.carevoice.alert.infrastructure.migrations.MongoMigrationRunner
import com.carevoice.alert.infrastructure.persistence.MongoAlertRepository
import com.carevoice.alert.infrastructure.persistence.MongoClientFactory
import com.carevoice.alert.infrastructure.persistence.MongoHealthCheck
import com.carevoice.alert.infrastructure.persistence.MongoProcessedEventRepository
import com.carevoice.alert.infrastructure.persistence.MongoProtocolChunkRepository
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import com.carevoice.alert.observability.MicrometerAlertMetrics
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("com.carevoice.alert.Application")

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

/**
 * The HTTP application. Pure wiring: plugins, use cases built on the given ports, routes, and the shutdown order.
 *
 * Shutdown (SIGTERM → engine stop): readiness flips to DOWN first, the pod keeps serving for
 * [Dependencies.readinessPropagationDelay] so the load balancer stops routing to it, [Dependencies.beforeDrain] stops
 * the AMQP consumer after its in-flight messages, and only then does the engine drain in-flight HTTP requests.
 */
fun Application.module(deps: Dependencies) {
    installHttpPlugins(deps.jwt, deps.rateLimitPerMinute, deps.meterRegistry)
    installRequestDeadline(deps.requestTimeout)
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

/**
 * Entry point. `alert-service migrate` applies pending Mongo migrations and exits (Kubernetes pre-upgrade Job, compose
 * `migrate` one-shot); without arguments the service starts.
 */
fun main(args: Array<String>) {
    val config =
        try {
            AppConfig.load(System.getenv())
        } catch (e: ConfigException) {
            log.error(e.message)
            exitProcess(EXIT_CONFIG_ERROR)
        }
    when (args.toList()) {
        listOf("migrate") -> exitProcess(runMigrations(config))

        emptyList<String>() -> serve(config)

        else -> {
            log.event(Level.ERROR, null, "Unknown arguments; expected none or 'migrate'", "arguments" to args.toList())
            exitProcess(EXIT_USAGE)
        }
    }
}

private fun runMigrations(config: AppConfig): Int {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val client = MongoClientFactory.create(config.mongo, registry)
    return try {
        val applied = runBlocking { MongoMigrationRunner(client.getDatabase(config.mongo.database)).migrate() }
        log.event(Level.INFO, LogType.DB_OPERATION, "Mongo migrations finished", "applied" to applied)
        0
    } catch (e: Exception) {
        log.event(Level.ERROR, LogType.DB_OPERATION, "Mongo migrations failed", cause = e)
        1
    } finally {
        client.close()
    }
}

private fun serve(config: AppConfig) {
    log.info("Starting alert-service with {}", config)
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val mongoClient = MongoClientFactory.create(config.mongo, registry)
    val database = mongoClient.getDatabase(config.mongo.database)

    // Fail fast before accepting traffic: a schema the code does not match must stop the pod, not corrupt data.
    runBlocking {
        val runner = MongoMigrationRunner(database)
        if (config.mongo.migrateOnStartup) runner.migrate() else runner.validate()
    }

    val clock = Clock.systemUTC()
    val alerts = MongoAlertRepository(database)
    val processedEvents = MongoProcessedEventRepository(database)
    val processObservationEvent =
        ProcessObservationEvent(
            evaluator = AlertEvaluator.default(),
            alerts = alerts,
            processedEvents = processedEvents,
            clock = clock,
            ids = IdGenerator.RANDOM_UUID,
            metrics = MicrometerAlertMetrics(registry),
        )
    val guidanceHttpClient = GuidanceHttpClient.create()
    val embedder = GuidanceHttpClient.embedder(config.guidance, guidanceHttpClient, registry)
    val protocolChunks = MongoProtocolChunkRepository(database)
    runBlocking { ingestProtocols(embedder, protocolChunks) }
    val guidanceWriter = GuidanceHttpClient.writer(config.guidance, guidanceHttpClient, registry)

    val consumerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val consumer = ObservationEventConsumer(config.rabbitmq, processObservationEvent, registry)

    val deps =
        Dependencies(
            jwt = JwtSettings(config.jwt.secret, config.jwt.issuer, config.jwt.audience),
            rateLimitPerMinute = config.rateLimitPerMinute,
            requestTimeout = config.requestTimeout,
            meterRegistry = registry,
            scrapeMetrics = { registry.scrape() },
            alerts = alerts,
            processedEvents = processedEvents,
            healthChecks = listOf(MongoHealthCheck(database), consumer),
            clock = clock,
            processObservationEvent = processObservationEvent,
            embedder = embedder,
            protocolChunks = protocolChunks,
            guidanceWriter = guidanceWriter,
            readinessPropagationDelay = READINESS_PROPAGATION_DELAY,
            beforeDrain = { consumer.stop(CONSUMER_STOP_TIMEOUT) },
        )

    val server =
        embeddedServer(Netty, port = config.httpPort) {
            module(deps)
        }
    consumer.start(consumerScope)

    Runtime.getRuntime().addShutdownHook(
        Thread({
            server.stop(GRACE_PERIOD.inWholeMilliseconds, DRAIN_TIMEOUT.inWholeMilliseconds)
            consumerScope.cancel()
            guidanceHttpClient.close()
            mongoClient.close()
            log.info("alert-service stopped")
        }, "alert-service-shutdown"),
    )
    server.start(wait = true)
}

/**
 * Builds the guidance knowledge base before the pod takes traffic. A failure (e.g. the embedding provider is down) is
 * logged and the service starts anyway: guidance is advisory, alerting must never depend on it. An earlier corpus of
 * the same model stays usable; otherwise guidance answers NO_MATCHING_PROTOCOL until the next start.
 */
private suspend fun ingestProtocols(embedder: Embedder, repository: ProtocolChunkRepository) {
    try {
        val result = IngestProtocols(ClasspathProtocolSource(), embedder, repository).execute()
        log.event(
            Level.INFO,
            LogType.BUSINESS,
            "Protocol knowledge base ready",
            "chunks" to result.chunks,
            "embeddingModel" to embedder.model,
            "reembedded" to result.changed,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.event(
            Level.WARN,
            LogType.EXTERNAL_REQUEST,
            "Protocol ingestion failed; guidance degrades until the next start",
            "embeddingModel" to embedder.model,
            cause = e,
        )
    }
}

private const val EXIT_CONFIG_ERROR = 2
private const val EXIT_USAGE = 64
private val READINESS_PROPAGATION_DELAY = 5.seconds
private val CONSUMER_STOP_TIMEOUT = 10.seconds
private val GRACE_PERIOD = 1.seconds

/** In-flight HTTP requests get this long to finish (docs/CONTRACTS.md, section 6). */
private val DRAIN_TIMEOUT = 10.seconds
