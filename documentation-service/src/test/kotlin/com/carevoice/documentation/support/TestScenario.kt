package com.carevoice.documentation.support

import com.carevoice.documentation.api.Dependencies
import com.carevoice.documentation.api.HttpSettings
import com.carevoice.documentation.application.AuthenticateUser
import com.carevoice.documentation.application.ConfirmObservation
import com.carevoice.documentation.application.EventOutbox
import com.carevoice.documentation.application.GetResident
import com.carevoice.documentation.application.GetTranscript
import com.carevoice.documentation.application.ListResidentObservations
import com.carevoice.documentation.application.ObservationStream
import com.carevoice.documentation.application.QueryObservations
import com.carevoice.documentation.application.Readiness
import com.carevoice.documentation.application.ResidentRepository
import com.carevoice.documentation.application.SubmitTranscript
import com.carevoice.documentation.infrastructure.auth.BcryptPasswordHasher
import com.carevoice.documentation.infrastructure.auth.JwtIssuer
import com.carevoice.documentation.infrastructure.cache.CachingResidentRepository
import com.carevoice.documentation.infrastructure.cache.CaffeineObservationQueryCache
import com.carevoice.documentation.infrastructure.crypto.AesGcmFieldEncryptor
import com.carevoice.documentation.infrastructure.health.PostgresHealthProbe
import com.carevoice.documentation.infrastructure.persistence.JdbcTransactions
import com.carevoice.documentation.infrastructure.persistence.PostgresAuditLog
import com.carevoice.documentation.infrastructure.persistence.PostgresEventOutbox
import com.carevoice.documentation.infrastructure.persistence.PostgresObservationRepository
import com.carevoice.documentation.infrastructure.persistence.PostgresResidentRepository
import com.carevoice.documentation.infrastructure.persistence.PostgresTranscriptRepository
import com.carevoice.documentation.infrastructure.persistence.PostgresUserRepository
import com.carevoice.documentation.infrastructure.redis.InMemoryRateLimiter
import com.carevoice.documentation.infrastructure.redis.LocalCacheInvalidationBus
import com.carevoice.documentation.infrastructure.redis.LocalObservationBroadcaster
import com.carevoice.documentation.infrastructure.tracing.MdcCorrelationProvider
import com.carevoice.documentation.module
import com.carevoice.documentation.outbox.EventPublisher
import com.carevoice.documentation.outbox.MutableClock
import com.carevoice.documentation.outbox.OutboxEvent
import com.carevoice.documentation.outbox.PublishResult
import com.carevoice.domain.application.TranscriptProcessor
import com.carevoice.domain.model.ObservationId
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Deterministic ids: a per-scenario random high half (unique in the shared database) and a counter 1, 2, 3, ... */
class SequentialIds(private val high: Long = Random.nextLong()) {
    private val counter = AtomicLong()

    fun next(): UUID = UUID(high, counter.incrementAndGet())

    /** How many ids were handed out so far; [expected] of this value is the latest one. */
    val handedOut: Int get() = counter.get().toInt()

    /** The [n]-th id this generator hands out (1-based), for expected bodies. */
    fun expected(n: Int): UUID = UUID(high, n.toLong())
}

/** Records every event handed to it (relay tests and two-instance tests); always reports success. */
class RecordingEventPublisher : EventPublisher {
    val published = CopyOnWriteArrayList<OutboxEvent>()

    override suspend fun publish(event: OutboxEvent): PublishResult {
        published += event
        return PublishResult.Published
    }
}

/**
 * One test's world: a fresh tenant with two wards (A, B), residents (A1, A2 in ward A; B1 in ward B), users, a
 * [MutableClock], sequential ids and the fully wired [Dependencies] against the real PostgreSQL schema.
 *
 * Single-instance wiring: the in-process cache bus and broadcaster and an in-memory rate limiter stand in for the
 * Redis adapters (those have their own tests); everything else is the production adapter.
 *
 * [residents] and [outbox] decorate the real adapters, which is how a test injects a throwing port (5xx smoke,
 * rollback checks) without replacing the rest of the wiring.
 */
class TestScenario private constructor(
    residents: (ResidentRepository) -> ResidentRepository,
    outbox: (EventOutbox) -> EventOutbox,
    httpSettings: HttpSettings,
) {
    private val suffix = UUID.randomUUID().toString().take(8)
    val tenantId = "t-$suffix"
    val wardA = "$tenantId-ward-a"
    val wardB = "$tenantId-ward-b"
    val residentA1 = "$tenantId-r1"
    val residentA2 = "$tenantId-r2"
    val residentB1 = "$tenantId-r4"
    val residentNames = mapOf(
        residentA1 to "Margaret Smith",
        residentA2 to "Harold Jones",
        residentB1 to "Walter Taylor",
    )
    val nurse = "nurse.$suffix"
    val lead = "lead.$suffix"
    val nurseWardB = "nurse-b.$suffix"

    val clock = MutableClock()
    val transcriptIds = SequentialIds()
    val observationIds = SequentialIds()
    val eventIds = SequentialIds()
    val encryptor = AesGcmFieldEncryptor("v1", mapOf("v1" to ByteArray(32) { it.toByte() }))
    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val hasher = BcryptPasswordHasher(cost = 4)

    val deps: Dependencies

    init {
        seed()
        val db = TestDatabase.dataSource
        val transactions = JdbcTransactions(db)
        val cacheBus = LocalCacheInvalidationBus()
        val broadcaster = LocalObservationBroadcaster()
        val cachingResidents = CachingResidentRepository(PostgresResidentRepository(db, encryptor), meterRegistry)
        val residentRepository = residents(cachingResidents)
        val queryCache = CaffeineObservationQueryCache(meterRegistry)
        cacheBus.subscribe(cachingResidents::onInvalidation)
        cacheBus.subscribe(queryCache::onInvalidation)
        val transcripts = PostgresTranscriptRepository(db, encryptor)
        val observations = PostgresObservationRepository(db)
        val processor =
            TranscriptProcessor(aiFallback = null, clock = clock, newId = { ObservationId(observationIds.next()) })

        deps =
            Dependencies(
                authenticateUser = AuthenticateUser(
                    PostgresUserRepository(db),
                    hasher,
                    JwtIssuer(TestTokens.jwtConfig, clock),
                ),
                getResident = GetResident(residentRepository),
                submitTranscript =
                SubmitTranscript(
                    residents = residentRepository,
                    transcripts = transcripts,
                    observations = observations,
                    outbox = outbox(PostgresEventOutbox(clock)),
                    audit = PostgresAuditLog(db),
                    transactions = transactions,
                    processor = processor,
                    broadcaster = broadcaster,
                    cacheBus = cacheBus,
                    correlation = MdcCorrelationProvider(),
                    meterRegistry = meterRegistry,
                    clock = clock,
                    newTranscriptId = transcriptIds::next,
                    newEventId = eventIds::next,
                ),
                getTranscript = GetTranscript(transcripts),
                listResidentObservations = ListResidentObservations(residentRepository, observations),
                queryObservations = QueryObservations(observations, queryCache),
                confirmObservation = ConfirmObservation(
                    observations,
                    PostgresAuditLog(db),
                    transactions,
                    cacheBus,
                    clock,
                ),
                observationStream = ObservationStream(broadcaster, observations),
                readiness = Readiness(listOf(PostgresHealthProbe(db))),
                rateLimiter = InMemoryRateLimiter(clock),
                jwt = TestTokens.jwtConfig,
                meterRegistry = meterRegistry,
                clock = clock,
                http = httpSettings,
            )
    }

    private fun seed() {
        TestDatabase.execute("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "Test tenant $suffix")
        TestDatabase.execute("INSERT INTO wards (id, tenant_id, name) VALUES (?, ?, ?)", wardA, tenantId, "Maple Ward")
        TestDatabase.execute("INSERT INTO wards (id, tenant_id, name) VALUES (?, ?, ?)", wardB, tenantId, "Oak Ward")
        listOf(residentA1 to wardA, residentA2 to wardA, residentB1 to wardB).forEach { (id, ward) ->
            TestDatabase.execute(
                "INSERT INTO residents (id, tenant_id, ward_id, display_name_enc) VALUES (?, ?, ?, ?)",
                id,
                tenantId,
                ward,
                encryptor.encrypt(residentNames.getValue(id)),
            )
        }
        val hash = hasher.hash(PASSWORD)
        listOf(
            Triple(nurse, wardA, "NURSE"),
            Triple(lead, wardA, "WARD_LEAD"),
            Triple(nurseWardB, wardB, "NURSE"),
        ).forEach { (user, ward, role) ->
            TestDatabase.execute(
                "INSERT INTO users (username, tenant_id, ward_id, role, password_hash) VALUES (?, ?, ?, ?, ?)",
                user,
                tenantId,
                ward,
                role,
                hash,
            )
        }
    }

    /** A valid NURSE token for ward A. */
    fun nurseToken(): String = TestTokens.token(clock, nurse, tenantId, wardA, listOf("NURSE"))

    fun leadToken(): String = TestTokens.token(clock, lead, tenantId, wardA, listOf("WARD_LEAD"))

    /** A token for ward A with arbitrary claims, for the authentication and authorization contracts. */
    fun token(
        roles: List<String> = listOf("NURSE"),
        ward: String = wardA,
        issuer: String = TestTokens.ISSUER,
        audience: String = TestTokens.AUDIENCE,
        secret: String = TestTokens.SECRET,
        expiresIn: Duration = Duration.ofMinutes(15),
    ): String = TestTokens.token(clock, nurse, tenantId, ward, roles, issuer, audience, secret, expiresIn)

    companion object {
        const val PASSWORD = "correct-horse-battery"

        fun create(
            residents: (ResidentRepository) -> ResidentRepository = { it },
            outbox: (EventOutbox) -> EventOutbox = { it },
            httpSettings: HttpSettings = HttpSettings(),
        ): TestScenario = TestScenario(residents, outbox, httpSettings)
    }
}

/** Runs [block] against the real HTTP module wired for [scenario] (Ktor `testApplication`). */
fun withApp(
    scenario: TestScenario = TestScenario.create(),
    block: suspend ApplicationTestBuilder.(TestScenario) -> Unit,
) = testApplication {
    application { module(scenario.deps) }
    block(scenario)
}
