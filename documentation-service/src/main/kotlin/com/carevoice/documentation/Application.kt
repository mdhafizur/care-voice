package com.carevoice.documentation

import com.carevoice.documentation.api.Dependencies
import com.carevoice.documentation.api.HttpSettings
import com.carevoice.documentation.api.httpModule
import com.carevoice.documentation.api.routes.LOGIN_SCOPE
import com.carevoice.documentation.application.AuthenticateUser
import com.carevoice.documentation.application.CacheInvalidationBus
import com.carevoice.documentation.application.ConfirmObservation
import com.carevoice.documentation.application.DemoSeeder
import com.carevoice.documentation.application.GetResident
import com.carevoice.documentation.application.GetTranscript
import com.carevoice.documentation.application.HealthProbe
import com.carevoice.documentation.application.ListResidentObservations
import com.carevoice.documentation.application.ObservationBroadcaster
import com.carevoice.documentation.application.ObservationStream
import com.carevoice.documentation.application.QueryObservations
import com.carevoice.documentation.application.Readiness
import com.carevoice.documentation.application.SubmitTranscript
import com.carevoice.documentation.application.TranscriptRetentionJob
import com.carevoice.documentation.config.AiConfig
import com.carevoice.documentation.config.AiProvider
import com.carevoice.documentation.config.AppConfig
import com.carevoice.documentation.config.ConfigException
import com.carevoice.documentation.config.EventTransport
import com.carevoice.documentation.infrastructure.ai.AiSettings
import com.carevoice.documentation.infrastructure.ai.ClaudeVitalExtractor
import com.carevoice.documentation.infrastructure.ai.GeminiVitalExtractor
import com.carevoice.documentation.infrastructure.ai.HttpAiVitalExtractor
import com.carevoice.documentation.infrastructure.alert.AlertServiceClient
import com.carevoice.documentation.infrastructure.amqp.AmqpEventPublisher
import com.carevoice.documentation.infrastructure.auth.BcryptPasswordHasher
import com.carevoice.documentation.infrastructure.auth.JwtIssuer
import com.carevoice.documentation.infrastructure.auth.ServiceTokenProvider
import com.carevoice.documentation.infrastructure.cache.CachingResidentRepository
import com.carevoice.documentation.infrastructure.cache.CaffeineObservationQueryCache
import com.carevoice.documentation.infrastructure.crypto.AesGcmFieldEncryptor
import com.carevoice.documentation.infrastructure.health.FlagHealthProbe
import com.carevoice.documentation.infrastructure.health.PostgresHealthProbe
import com.carevoice.documentation.infrastructure.http.HttpClients
import com.carevoice.documentation.infrastructure.persistence.Database
import com.carevoice.documentation.infrastructure.persistence.JdbcTransactions
import com.carevoice.documentation.infrastructure.persistence.Migrations
import com.carevoice.documentation.infrastructure.persistence.PostgresAuditLog
import com.carevoice.documentation.infrastructure.persistence.PostgresDemoDataRepository
import com.carevoice.documentation.infrastructure.persistence.PostgresEventOutbox
import com.carevoice.documentation.infrastructure.persistence.PostgresObservationRepository
import com.carevoice.documentation.infrastructure.persistence.PostgresResidentRepository
import com.carevoice.documentation.infrastructure.persistence.PostgresTranscriptRepository
import com.carevoice.documentation.infrastructure.persistence.PostgresUserRepository
import com.carevoice.documentation.infrastructure.redis.InMemoryRateLimiter
import com.carevoice.documentation.infrastructure.redis.RedisCacheInvalidationBus
import com.carevoice.documentation.infrastructure.redis.RedisConnections
import com.carevoice.documentation.infrastructure.redis.RedisObservationBroadcaster
import com.carevoice.documentation.infrastructure.redis.RedisRateLimiter
import com.carevoice.documentation.infrastructure.tracing.MdcCorrelationProvider
import com.carevoice.documentation.outbox.EventPublisher
import com.carevoice.documentation.outbox.OutboxRelay
import com.carevoice.documentation.outbox.OutboxSettings
import com.carevoice.domain.application.TranscriptProcessor
import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("com.carevoice.documentation.Application")

/**
 * Entry point.
 * - no argument: run the service.
 * - `migrate`: apply database migrations through `DATABASE_MIGRATION_URL` and exit (0 on success, 1 on failure);
 *   used by the Kubernetes pre-upgrade Job and the compose `migrate` one-shot.
 */
fun main(args: Array<String>) {
    when (args.singleOrNull()) {
        null -> runService()

        "migrate" -> exitProcess(runMigrations())

        else -> {
            System.err.println("Usage: documentation-service [migrate]")
            exitProcess(2)
        }
    }
}

/** The HTTP module used by `main` and by tests (`testApplication { application { module(deps) } }`). */
fun Application.module(deps: Dependencies) = httpModule(deps)

private fun runMigrations(): Int = try {
    Migrations(AppConfig.loadDatabase()).migrate()
    0
} catch (e: ConfigException) {
    log.error(e.message)
    1
} catch (e: Exception) {
    log.error("Database migration failed", e)
    1
}

private fun runService() {
    val config =
        try {
            AppConfig.load()
        } catch (e: ConfigException) {
            // Fail fast with every problem at once; never print values (they may be secrets).
            log.error(e.message)
            exitProcess(1)
        }
    log.info("Starting documentation-service with {}", config)

    // Ktor would otherwise register its own shutdown hook and stop the server immediately on SIGTERM, before
    // readiness has flipped; our hook below runs the full ordered shutdown instead.
    System.setProperty("io.ktor.server.engine.ShutdownHook", "false")
    val system = ServiceSystem.create(config)
    val server =
        embeddedServer(Netty, port = config.httpPort, host = "0.0.0.0") {
            module(system.dependencies)
        }
    system.startBackground()
    server.start(wait = false)

    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread({
            runBlocking {
                system.shutdown { server.stop(DRAIN.inWholeMilliseconds, DRAIN.inWholeMilliseconds + 5_000) }
            }
            stopped.countDown()
        }, "graceful-shutdown"),
    )
    stopped.await()
}

/** In-flight requests get this long to finish after the server stops accepting (docs/CONTRACTS.md, section 6). */
private val DRAIN: Duration = 10.seconds

/**
 * The wired object graph (manual constructor injection, no DI framework): every adapter is created here and handed
 * to the use cases through their ports. Factory pattern for the event publisher (EVENT_TRANSPORT) and HTTP clients.
 */
class ServiceSystem private constructor(
    val dependencies: Dependencies,
    private val database: Database,
    private val migrations: Migrations,
    private val redis: RedisConnections,
    private val relay: OutboxRelay,
    private val publisher: EventPublisher,
    private val retentionJob: TranscriptRetentionJob,
    private val demoSeeder: DemoSeeder?,
    private val demoPassword: String?,
    private val httpClients: List<HttpClient>,
    private val scope: CoroutineScope,
) {
    fun startBackground() {
        migrations.onStartup()
        redis.start(scope)
        if (demoSeeder != null && demoPassword != null) runBlocking { demoSeeder.seed(demoPassword) }
        // Connect now, not on the first publish: readiness must reflect the broker, not whether events happened.
        (publisher as? AmqpEventPublisher)?.connectInBackground(scope)
        relay.start(scope)
        retentionJob.start(scope)
    }

    /**
     * Graceful shutdown (docs/CONTRACTS.md, sections 6 and 9b):
     * 1. readiness → 503 so the load balancer stops routing here, and give it a moment to notice;
     * 2. close SSE streams (clients reconnect to another pod with Last-Event-ID);
     * 3. stop accepting and drain in-flight requests ([stopServer], 10 s);
     * 4. stop the outbox relay after its current batch, and the retention job;
     * 5. close the broker, Redis, HTTP clients and the connection pools.
     */
    suspend fun shutdown(stopServer: () -> Unit) {
        log.info("Shutdown started")
        dependencies.readiness.markShuttingDown()
        delay(READINESS_PROPAGATION)
        dependencies.observationStream.closeAll()
        stopServer()
        withTimeoutOrNull(RELAY_STOP_TIMEOUT) { relay.stop() }
        retentionJob.stop()
        (publisher as? AmqpEventPublisher)?.close()
        redis.close()
        httpClients.forEach { it.close() }
        scope.cancel()
        database.close()
        log.info("Shutdown finished")
    }

    companion object {
        private val READINESS_PROPAGATION: Duration = 3.seconds
        private val RELAY_STOP_TIMEOUT: Duration = 15.seconds

        fun create(config: AppConfig, clock: Clock = Clock.systemUTC()): ServiceSystem {
            val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val database = Database.create(config.database, config.readDatabase, meterRegistry)
            val transactions = JdbcTransactions(database.primary)
            val encryptor = AesGcmFieldEncryptor(config.encryption)
            val hasher = BcryptPasswordHasher()
            val jwtIssuer = JwtIssuer(config.jwt, clock)

            val redis = RedisConnections(config.redisUrl, meterRegistry)
            val cacheBus: CacheInvalidationBus = RedisCacheInvalidationBus(redis)
            val broadcaster: ObservationBroadcaster = RedisObservationBroadcaster(redis)
            val rateLimiter =
                RedisRateLimiter(redis, meterRegistry, InMemoryRateLimiter(clock), floorScopes = setOf(LOGIN_SCOPE))

            val residents =
                CachingResidentRepository(PostgresResidentRepository(database.primary, encryptor), meterRegistry)
            val queryCache = CaffeineObservationQueryCache(meterRegistry)
            cacheBus.subscribe(residents::onInvalidation)
            cacheBus.subscribe(queryCache::onInvalidation)

            val transcripts = PostgresTranscriptRepository(database.primary, encryptor)
            val observations = PostgresObservationRepository(database.primary)
            val readObservations = PostgresObservationRepository(database.read)

            val httpClients = mutableListOf<HttpClient>()
            val aiExtractor =
                if (config.ai.enabled && config.ai.apiKey != null) {
                    aiVitalExtractor(config.ai, config.ai.apiKey, meterRegistry) { timeout ->
                        HttpClients.create(timeout).also { httpClients += it }
                    }
                } else {
                    null
                }
            val processor = TranscriptProcessor(aiFallback = aiExtractor, clock = clock)

            val serviceTokens = ServiceTokenProvider(jwtIssuer, clock)
            val publisher: EventPublisher =
                when (config.eventTransport) {
                    EventTransport.AMQP -> AmqpEventPublisher(requireNotNull(config.rabbitmqUrl), meterRegistry)

                    EventTransport.HTTP -> {
                        val client = HttpClients.create().also { httpClients += it }
                        AlertServiceClient(
                            requireNotNull(config.alertServiceUrl),
                            client,
                            serviceTokens::token,
                            meterRegistry,
                        )
                    }
                }
            val relay = OutboxRelay(database.primary, publisher, OutboxSettings(), meterRegistry, clock)

            val probes =
                buildList<HealthProbe> {
                    add(PostgresHealthProbe(database.primary))
                    if (database.hasReplica) {
                        add(
                            PostgresHealthProbe(database.read, name = "databaseReplica", critical = false),
                        )
                    }
                    add(FlagHealthProbe("redis") { redis.isUp })
                    if (publisher is AmqpEventPublisher) add(FlagHealthProbe("rabbitmq") { publisher.isConnected })
                }

            val dependencies =
                Dependencies(
                    authenticateUser = AuthenticateUser(PostgresUserRepository(database.primary), hasher, jwtIssuer),
                    getResident = GetResident(residents),
                    submitTranscript =
                    SubmitTranscript(
                        residents = residents,
                        transcripts = transcripts,
                        observations = observations,
                        outbox = PostgresEventOutbox(clock),
                        audit = PostgresAuditLog(database.primary),
                        transactions = transactions,
                        processor = processor,
                        broadcaster = broadcaster,
                        cacheBus = cacheBus,
                        correlation = MdcCorrelationProvider(),
                        meterRegistry = meterRegistry,
                        clock = clock,
                    ),
                    getTranscript = GetTranscript(transcripts),
                    listResidentObservations = ListResidentObservations(residents, observations),
                    queryObservations = QueryObservations(readObservations, queryCache),
                    confirmObservation = ConfirmObservation(
                        observations,
                        PostgresAuditLog(database.primary),
                        transactions,
                        cacheBus,
                        clock,
                    ),
                    observationStream = ObservationStream(broadcaster, observations),
                    readiness = Readiness(probes),
                    rateLimiter = rateLimiter,
                    jwt = config.jwt,
                    meterRegistry = meterRegistry,
                    clock = clock,
                    http =
                    HttpSettings(
                        rateLimitPerMinute = config.rateLimitPerMinute,
                        requestTimeout = config.requestTimeout,
                    ),
                )

            return ServiceSystem(
                dependencies = dependencies,
                database = database,
                migrations = Migrations(config.database),
                redis = redis,
                relay = relay,
                publisher = publisher,
                retentionJob = TranscriptRetentionJob(transcripts, transactions, config.transcriptRetention, clock),
                demoSeeder = if (config.demoSeed.enabled) {
                    DemoSeeder(
                        PostgresDemoDataRepository(database.primary, encryptor),
                        transactions,
                        hasher,
                    )
                } else {
                    null
                },
                demoPassword = config.demoSeed.password,
                httpClients = httpClients,
                scope = scope,
            )
        }
    }
}

/**
 * Factory: the AI fallback adapter for `AI_PROVIDER`. Gemini gets a longer default timeout than Claude because Gemini 3
 * models cannot switch thinking off completely; at the `low` level an answer still takes a little longer.
 * `AI_TIMEOUT_MS` overrides either default.
 */
private fun aiVitalExtractor(
    ai: AiConfig,
    apiKey: String,
    meterRegistry: MeterRegistry,
    httpClient: (Duration) -> HttpClient,
): HttpAiVitalExtractor = when (ai.provider) {
    AiProvider.CLAUDE -> {
        val settings = AiSettings(apiKey = apiKey, model = ai.model, timeout = ai.timeout ?: 3.seconds)
        ClaudeVitalExtractor(settings, httpClient(settings.timeout), meterRegistry)
    }

    AiProvider.GEMINI -> {
        val settings =
            AiSettings(
                apiKey = apiKey,
                model = ai.model,
                timeout = ai.timeout ?: 5.seconds,
                baseUrl = GeminiVitalExtractor.DEFAULT_BASE_URL,
                thinkingLevel = ai.thinkingLevel,
            )
        GeminiVitalExtractor(settings, httpClient(settings.timeout), meterRegistry)
    }
}
