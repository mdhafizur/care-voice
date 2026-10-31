package com.carevoice.alert.support

import com.carevoice.alert.Dependencies
import com.carevoice.alert.api.JwtSettings
import com.carevoice.alert.application.AlertRepository
import com.carevoice.alert.application.DependencyHealthCheck
import com.carevoice.alert.application.GuidanceWriter
import com.carevoice.alert.application.IdGenerator
import com.carevoice.alert.application.ProtocolChunkRepository
import com.carevoice.alert.infrastructure.migrations.MongoMigrationRunner
import com.carevoice.alert.infrastructure.persistence.MongoAlertRepository
import com.carevoice.alert.infrastructure.persistence.MongoProcessedEventRepository
import com.carevoice.alert.module
import com.carevoice.testsupport.OpenApiContract
import com.carevoice.testsupport.TestDatabases
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.bson.Document
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Fixed values every alert-service test shares; time and ids are deterministic. */
object TestFixtures {
    val NOW: Instant = Instant.parse("2026-10-25T10:00:00Z")
    val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    const val JWT_SECRET = "alert-service-test-secret-of-at-least-32-bytes"
    const val ISSUER = "carevoice-auth"
    const val AUDIENCE = "carevoice"
    const val TENANT = "sunrise-care"
    const val WARD = "ward-a"
    const val OTHER_WARD = "ward-b"
    const val WARD_LEAD = "lead.clara"
    const val SERVICE_SUBJECT = "documentation-service"

    val openApi: OpenApiContract by lazy { OpenApiContract("openapi/alert-service.yaml") }
}

/** Deterministic id generator: `alert-0001`, `alert-0002`, ... */
class SequentialIds(private val prefix: String = "alert") : IdGenerator {
    private val counter = AtomicInteger()

    override fun next(): String = "%s-%04d".format(prefix, counter.incrementAndGet())

    /** The id the n-th call to [next] returns (1-based), for expected bodies. */
    fun idAt(n: Int): String = "%s-%04d".format(prefix, n)
}

/** A readiness check whose result the test controls. */
class SwitchableHealthCheck(override val name: String, @Volatile var healthy: Boolean = true) : DependencyHealthCheck {
    override suspend fun isHealthy(): Boolean = healthy
}

/** The embedded (or containerised) mongod from [TestDatabases], one migrated database per test class. */
object TestMongo {
    val client: MongoClient by lazy { MongoClient.create(TestDatabases.mongo.connectionString) }

    /** A new, empty database with every migration applied. Names stay under Mongo's 63-character limit. */
    fun freshDatabase(owner: String): MongoDatabase {
        val safeOwner = owner.replace(Regex("[^A-Za-z0-9]"), "_").takeLast(30)
        val database = client.getDatabase("t_${safeOwner}_${UUID.randomUUID().toString().take(8)}")
        runBlocking { MongoMigrationRunner(database).migrate() }
        return database
    }
}

/**
 * Test application builder: the real [module] on a real Mongo database (unique per test class, migrations applied),
 * with a fixed clock and deterministic ids. Every [run] starts from empty collections, so tests in one class do not see
 * each other's alerts.
 */
class AlertTestApp private constructor(owner: String) {
    val database: MongoDatabase = TestMongo.freshDatabase(owner)
    val alerts: MongoAlertRepository = MongoAlertRepository(database)
    val processedEvents: MongoProcessedEventRepository = MongoProcessedEventRepository(database)

    fun dependencies(
        ids: IdGenerator = SequentialIds(),
        rateLimitPerMinute: Int = 10_000,
        alertRepository: AlertRepository = alerts,
        eventConcurrency: Int = 64,
        healthChecks: List<DependencyHealthCheck> =
            listOf(SwitchableHealthCheck("mongo"), SwitchableHealthCheck("rabbitmq")),
        protocolChunks: ProtocolChunkRepository? = null,
        guidanceWriter: GuidanceWriter? = null,
    ): Dependencies {
        val registry = SimpleMeterRegistry()
        return Dependencies(
            jwt = JwtSettings(TestFixtures.JWT_SECRET.toByteArray(), TestFixtures.ISSUER, TestFixtures.AUDIENCE),
            rateLimitPerMinute = rateLimitPerMinute,
            meterRegistry = registry,
            scrapeMetrics = { "" },
            alerts = alertRepository,
            processedEvents = processedEvents,
            healthChecks = healthChecks,
            clock = TestFixtures.CLOCK,
            ids = ids,
            eventConcurrency = eventConcurrency,
            protocolChunks = protocolChunks,
            guidanceWriter = guidanceWriter,
        )
    }

    fun run(deps: Dependencies = dependencies(), block: suspend ApplicationTestBuilder.() -> Unit) {
        runBlocking { clear() }
        testApplication {
            application { module(deps) }
            block()
        }
    }

    suspend fun clear() {
        database.getCollection("alerts", Document::class.java).deleteMany(Document())
        database.getCollection("processed_events", Document::class.java).deleteMany(Document())
    }

    companion object {
        private val perClass = ConcurrentHashMap<String, AlertTestApp>()

        /** One database per test class (nested classes share their outer class's database). */
        fun forClass(testClass: Class<*>): AlertTestApp {
            val outer = generateSequence(testClass) { it.enclosingClass }.last()
            return perClass.computeIfAbsent(outer.name) { AlertTestApp(outer.simpleName) }
        }
    }
}
