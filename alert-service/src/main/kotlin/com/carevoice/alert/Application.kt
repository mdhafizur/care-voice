package com.carevoice.alert

import com.carevoice.alert.api.JwtSettings
import com.carevoice.alert.application.Embedder
import com.carevoice.alert.application.IdGenerator
import com.carevoice.alert.application.IngestProtocols
import com.carevoice.alert.application.ProcessObservationEvent
import com.carevoice.alert.application.ProtocolChunkRepository
import com.carevoice.alert.config.AppConfig
import com.carevoice.alert.config.ConfigException
import com.carevoice.alert.domain.AlertEvaluator
import com.carevoice.alert.infrastructure.amqp.DeadLetterReplayer
import com.carevoice.alert.infrastructure.amqp.ObservationEventConsumer
import com.carevoice.alert.infrastructure.guidance.ClasspathProtocolSource
import com.carevoice.alert.infrastructure.guidance.GuidanceHttpClient
import com.carevoice.alert.infrastructure.migrations.MongoMigrationRunner
import com.carevoice.alert.infrastructure.persistence.MongoAlertRepository
import com.carevoice.alert.infrastructure.persistence.MongoClientFactory
import com.carevoice.alert.infrastructure.persistence.MongoHealthCheck
import com.carevoice.alert.infrastructure.persistence.MongoProcessedEventRepository
import com.carevoice.alert.infrastructure.persistence.MongoProtocolChunkRepository
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import com.carevoice.alert.observability.MicrometerAlertMetrics
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("com.carevoice.alert.Application")

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
    val arguments = args.toList()
    val replayLimit = replayLimit(arguments)
    when {
        arguments.isEmpty() -> serve(config)

        arguments == listOf("migrate") -> exitProcess(runMigrations(config))

        replayLimit != null -> exitProcess(replayDeadLetters(config, replayLimit))

        else -> {
            log.event(
                Level.ERROR,
                null,
                "Unknown arguments; expected none, 'migrate' or '$REPLAY_COMMAND [limit]'",
                "arguments" to arguments,
            )
            exitProcess(EXIT_USAGE)
        }
    }
}

/** `replay-dead-letters` → every message; `replay-dead-letters <n>` (n >= 1) → at most n; anything else → null. */
internal fun replayLimit(args: List<String>): Int? = when {
    args == listOf(REPLAY_COMMAND) -> Int.MAX_VALUE
    args.size == 2 && args[0] == REPLAY_COMMAND -> args[1].toIntOrNull()?.takeIf { it >= 1 }
    else -> null
}

/** Moves dead-lettered events back to the queue (docs/CONTRACTS.md, section 8a); exit code 0 on success. */
private fun replayDeadLetters(config: AppConfig, limit: Int): Int = try {
    // The replayer logs every replayed event and a summary.
    DeadLetterReplayer(ObservationEventConsumer.defaultConnectionFactory(config.rabbitmq)).replay(limit)
    0
} catch (e: Exception) {
    log.event(Level.ERROR, LogType.EXTERNAL_REQUEST, "Dead-letter replay failed", cause = e)
    1
}

/** Runs the Mongo migrations (scripted schema changes) once and returns the exit code: 0 on success, 1 on failure. */
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

/**
 * Starts the HTTP server and the RabbitMQ event consumer, and blocks until the server stops.
 * Before it takes traffic, it applies or checks the Mongo migrations and loads the protocol knowledge base.
 * A shutdown hook stops the server, then the consumer, the guidance HTTP client and the Mongo client.
 */
private fun serve(config: AppConfig) {
    // Step 1: create the metrics registry and connect to MongoDB.
    log.info("Starting alert-service with {}", config)
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val mongoClient = MongoClientFactory.create(config.mongo, registry)
    val database = mongoClient.getDatabase(config.mongo.database)

    // Step 2: apply the database migrations, or only check them, depending on the config.
    // Fail fast before accepting traffic: a schema the code does not match must stop the pod, not corrupt data.
    runBlocking {
        val runner = MongoMigrationRunner(database)
        if (config.mongo.migrateOnStartup) runner.migrate() else runner.validate()
    }

    // Step 3: build the use case that turns observation events into alerts.
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
    // Step 4: set up care guidance. The embedder turns text into vectors (lists of numbers) for similarity
    // search. The protocol texts are loaded into the knowledge base, and the optional writer drafts the guidance.
    val guidanceHttpClient = GuidanceHttpClient.create()
    val embedder = GuidanceHttpClient.embedder(config.guidance, guidanceHttpClient, registry)
    val protocolChunks = MongoProtocolChunkRepository(database)
    runBlocking { ingestProtocols(embedder, protocolChunks) }
    val guidanceWriter = GuidanceHttpClient.writer(config.guidance, guidanceHttpClient, registry)

    // Step 5: create the consumer that reads observation events from RabbitMQ.
    val consumerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mongoHealth = MongoHealthCheck(database)
    // Pauses consumption while Mongo is down, so a long outage leaves the events waiting in the queue.
    val consumer =
        ObservationEventConsumer(config.rabbitmq, processObservationEvent, registry, pauseWhileDown = mongoHealth)

    // Step 6: collect everything the HTTP module needs.
    val deps =
        Dependencies(
            jwt = JwtSettings(config.jwt.secret, config.jwt.issuer, config.jwt.audience),
            rateLimitPerMinute = config.rateLimitPerMinute,
            requestTimeout = config.requestTimeout,
            meterRegistry = registry,
            scrapeMetrics = { registry.scrape() },
            alerts = alerts,
            processedEvents = processedEvents,
            healthChecks = listOf(mongoHealth, consumer),
            clock = clock,
            processObservationEvent = processObservationEvent,
            embedder = embedder,
            protocolChunks = protocolChunks,
            guidanceWriter = guidanceWriter,
            readinessPropagationDelay = READINESS_PROPAGATION_DELAY,
            beforeDrain = { consumer.stop(CONSUMER_STOP_TIMEOUT) },
        )

    // Step 7: create the HTTP server and start reading events.
    val server =
        embeddedServer(Netty, port = config.httpPort) {
            module(deps)
        }
    consumer.start(consumerScope)

    // Step 8: on JVM exit, stop the server with a grace period, then close the consumer scope and the clients.
    Runtime.getRuntime().addShutdownHook(
        Thread({
            server.stop(GRACE_PERIOD.inWholeMilliseconds, DRAIN_TIMEOUT.inWholeMilliseconds)
            consumerScope.cancel()
            guidanceHttpClient.close()
            mongoClient.close()
            log.info("alert-service stopped")
        }, "alert-service-shutdown"),
    )
    // Step 9: start the server and block here until it stops.
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

/** Exit code when the configuration is invalid; the migrate and replay commands exit with 1 when they fail. */
private const val EXIT_CONFIG_ERROR = 2

/**
 * `EX_USAGE` from the BSD sysexits convention: the command was called with wrong arguments.
 * https://man.freebsd.org/cgi/man.cgi?query=sysexits
 */
private const val EXIT_USAGE = 64
private const val REPLAY_COMMAND = "replay-dead-letters"

/**
 * How long the pod keeps serving with readiness DOWN before it drains. The shutdown budget in
 * deploy/helm/carevoice/values.yaml (readiness DOWN for 5 s, consumer stop up to 10 s, drain 10 s) must fit in the
 * pod's `terminationGracePeriodSeconds`.
 */
private val READINESS_PROPAGATION_DELAY = 5.seconds

/** Longest wait for the RabbitMQ consumer to finish its in-flight messages at shutdown (same budget as above). */
private val CONSUMER_STOP_TIMEOUT = 10.seconds

/** Ktor's `gracePeriodMillis`: time for activity to cool down before the server stops; [DRAIN_TIMEOUT] caps it. */
private val GRACE_PERIOD = 1.seconds

/** In-flight HTTP requests get this long to finish (docs/CONTRACTS.md, section 6). */
private val DRAIN_TIMEOUT = 10.seconds
