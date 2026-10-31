package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.ObservationFilter
import com.carevoice.documentation.application.PageCursor
import com.carevoice.documentation.application.PageRequest
import com.carevoice.documentation.outbox.OutboxRepository
import com.carevoice.documentation.support.ExplainingDataSource
import com.carevoice.documentation.support.QueryPlan
import com.carevoice.domain.model.ObservationStatus
import com.carevoice.domain.model.VitalType
import com.carevoice.testsupport.TestDatabases
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.sql.DataSource

/**
 * Plan regression tests (docs/indexing.md): every hot query, exactly as the repository sends it, must be served by the
 * index designed for it. [ExplainingDataSource] EXPLAINs each statement with its real bind values, so these tests
 * catch both a query change that no longer matches its index and a migration that drops or reorders one.
 *
 * The planner decides by statistics, so the data has production's shape (see [PlanTestDatabase]); on a nearly empty
 * table a sequential scan is the right plan and a plan test would prove nothing. What is asserted is the access path
 * (which index, no sequential scan, no sort where the index must deliver the order), never costs or timings: those
 * belong to the benchmark (`make db-bench`), not to CI.
 */
@DisplayName("Query plans")
class QueryPlanTest {
    private val database = ExplainingDataSource(PlanTestDatabase.dataSource)
    private val observations = PostgresObservationRepository(database)

    @BeforeEach
    fun forgetEarlierPlans() {
        database.clear()
    }

    @Nested
    @DisplayName("Resident history (GET /v1/residents/{id}/observations)")
    inner class ResidentHistory {
        @Test
        fun `should read the newest page in index order without sorting`() = runBlocking<Unit> {
            observations.listByResident(TENANT, WARD, RESIDENT, PageRequest(limit = 20))

            // Without the dependency statistics of V3 the planner estimates 1 row for the three correlated scope
            // columns and switches to BitmapAnd + Sort; this assertion is what keeps V3 from being "cleaned up".
            database.planOf("page") { it.contains("ORDER BY recorded_at DESC") }.assertServedBy(
                index = "observations_resident_recorded_idx",
                orderFromIndex = true,
            )
        }

        @Test
        fun `should count the resident's observations from an index`() = runBlocking<Unit> {
            observations.listByResident(TENANT, WARD, RESIDENT, PageRequest(limit = 20))

            database.planOf("count") { it.startsWith("SELECT count(*)") }.assertServedBy(
                index = "observations_resident_recorded_idx",
                orderFromIndex = false,
            )
        }
    }

    @Nested
    @DisplayName("Ward queries (QUERY /v1/observations)")
    inner class WardQueries {
        @Test
        fun `should continue a ward list by keyset inside the index condition`() = runBlocking<Unit> {
            val firstPage = observations.query(TENANT, WARD, ObservationFilter(), PageRequest(limit = 20))
            database.clear()

            observations.query(
                TENANT,
                WARD,
                ObservationFilter(),
                PageRequest(limit = 20, cursor = PageCursor.after(firstPage.items.last())),
            )

            val plan = database.planOf("keyset page") { it.contains("(recorded_at, id) < (?, ?)") }
            plan.assertServedBy(index = "observations_ward_recorded_idx", orderFromIndex = true)
            // The row comparison must bound the index scan, not filter rows after reading them from the start.
            assertThat(plan.indexConditions("observations_ward_recorded_idx"))
                .describedAs("index conditions of\n$plan")
                .anySatisfy { assertThat(it).contains("ROW(recorded_at, id) <") }
        }

        @Test
        fun `should filter by vital type while reading the ward index in order`() = runBlocking<Unit> {
            observations.query(TENANT, WARD, ObservationFilter(vitalTypes = setOf(VitalType.PULSE)), PageRequest())

            database.planOf("page") { it.contains("ORDER BY recorded_at DESC") }.assertServedBy(
                index = "observations_ward_recorded_idx",
                orderFromIndex = true,
            )
        }

        @Test
        fun `should serve the review queue from the partial index`() = runBlocking<Unit> {
            observations.query(
                TENANT,
                WARD,
                ObservationFilter(status = ObservationStatus.NEEDS_REVIEW),
                PageRequest(),
            )

            // A sort is allowed: the partial index ends at recorded_at, and sorting ~20 review rows by id is free.
            database.planOf("page") { it.contains("ORDER BY recorded_at DESC") }.assertServedBy(
                index = "observations_needs_review_idx",
                orderFromIndex = false,
            )
        }

        @Test
        fun `should replay events after the last seen id from the primary key and the ward index`() =
            runBlocking<Unit> {
                val newest = observations.query(TENANT, WARD, ObservationFilter(), PageRequest(limit = 50)).items
                database.clear()

                observations.findAfter(TENANT, WARD, newest.last().id, limit = 100)

                val plan = database.planOf("replay") { it.contains("ORDER BY recorded_at ASC") }
                plan.assertServedBy(index = "observations_ward_recorded_idx", orderFromIndex = true)
                assertThat(plan.indexes).describedAs("indexes read by\n$plan").contains("observations_pkey")
            }
    }

    @Nested
    @DisplayName("Outbox relay")
    inner class Outbox {
        private val outbox = OutboxRepository(database)

        @Test
        fun `should claim due events from the partial pending index`() {
            outbox.claim(now = LATER, leaseUntil = LATER.plusSeconds(30), limit = 50)

            database.planOf("claim") { it.contains("FOR UPDATE SKIP LOCKED") }.assertServedBy(
                index = "outbox_pending_idx",
                orderFromIndex = true,
            )
        }

        @Test
        fun `should compute the backlog gauges from the partial pending index`() {
            outbox.pendingStats()

            database.planOf("stats") { it.contains("FROM outbox WHERE status = 'PENDING'") }.assertServedBy(
                index = "outbox_pending_idx",
                orderFromIndex = false,
            )
        }
    }

    private fun QueryPlan.assertServedBy(index: String, orderFromIndex: Boolean) {
        assertThat(indexes).describedAs("indexes read by\n$this").contains(index)
        assertThat(nodeTypes).describedAs("plan\n$this").doesNotContain("Seq Scan")
        if (orderFromIndex) {
            assertThat(nodeTypes).describedAs("plan\n$this").doesNotContain("Sort", "Incremental Sort")
        }
    }

    private companion object {
        const val TENANT = "plan-tenant-0"
        const val WARD = "plan-ward-0"
        const val RESIDENT = "plan-resident-42"

        /** After every seeded outbox row became due. */
        val LATER: Instant = Instant.parse("2027-01-01T00:00:00Z")
    }
}

/**
 * The production schema (Flyway, all migrations) in a schema of its own, seeded once per JVM with the benchmark's
 * shape at a tenth of its size: 5 tenants, 50 wards, 2,500 residents, 100k observations (three per transcript, 1 %
 * needing review) and 33k outbox rows of which 33 are pending. VACUUM ANALYZE afterwards gives the planner the
 * statistics and visibility map autovacuum would. Seeding is server-side (`generate_series`) and takes about a second.
 */
private object PlanTestDatabase {
    private const val SCHEMA = "documentation_plan_test"

    val dataSource: DataSource by lazy {
        val postgres = TestDatabases.postgres
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                schema = SCHEMA
                maximumPoolSize = 4
                poolName = "documentation-plan-test"
            },
        ).also { seed(it) }
    }

    private fun seed(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val seeded =
                    statement.executeQuery("SELECT count(*) FROM tenants WHERE id = 'plan-tenant-0'").use { rows ->
                        rows.next()
                        rows.getInt(1) > 0
                    }
                if (!seeded) SEED.forEach { statement.execute(it) }
            }
        }
    }

    private const val START = "timestamptz '2025-10-01 00:00:00+00'"

    // r = resident of transcript t, spread pseudo-randomly (t * 7919 mod 2500); resident r lives in ward r / 50 of
    // tenant r / 500. Same generator as scripts/db-bench/postgres.sql.
    private val SEED =
        listOf(
            "INSERT INTO tenants (id, name) SELECT 'plan-tenant-' || t, 'Tenant ' || t FROM generate_series(0, 4) t",
            """
            INSERT INTO wards (id, tenant_id, name)
            SELECT 'plan-ward-' || w, 'plan-tenant-' || (w / 10), 'Ward ' || w FROM generate_series(0, 49) w
            """,
            """
            INSERT INTO residents (id, tenant_id, ward_id, display_name_enc)
            SELECT 'plan-resident-' || r, 'plan-tenant-' || (r / 500), 'plan-ward-' || (r / 50), 'k1:ciphertext'
              FROM generate_series(0, 2499) r
            """,
            """
            INSERT INTO transcripts (id, tenant_id, resident_id, created_by, idempotency_key, request_hash, text_enc,
                                     response_body, created_at)
            SELECT md5('plan-tr' || t)::uuid, 'plan-tenant-' || (r / 500), 'plan-resident-' || r, 'plan-nurse',
                   md5('plan-key' || t)::uuid, 'hash', 'k1:ciphertext', '{}', $START + t * interval '15 minutes'
              FROM generate_series(0, 33332) t, LATERAL (SELECT (t::bigint * 7919 % 2500)::int AS r) rr
            """,
            """
            INSERT INTO observations (id, tenant_id, ward_id, resident_id, transcript_id, vital_type, pulse_bpm, source,
                                      confidence, status, recorded_at)
            SELECT md5('plan-obs' || i)::uuid, 'plan-tenant-' || (r / 500), 'plan-ward-' || (r / 50),
                   'plan-resident-' || r, md5('plan-tr' || (i / 3))::uuid, 'PULSE', 60 + i % 40, 'RULES', 0.95,
                   CASE WHEN (i::bigint * 2654435761) % 100 = 0 THEN 'NEEDS_REVIEW' ELSE 'CONFIRMED' END,
                   $START + (i / 3) * interval '15 minutes'
              FROM generate_series(0, 99998) i, LATERAL (SELECT ((i / 3)::bigint * 7919 % 2500)::int AS r) rr
            """,
            """
            INSERT INTO outbox (id, aggregate_id, event_type, payload, status, next_attempt_at, created_at,
                                published_at)
            SELECT md5('plan-ev' || t)::uuid, md5('plan-tr' || t)::uuid, 'ObservationsRecorded', '{}',
                   CASE WHEN t >= 33300 THEN 'PENDING' ELSE 'PUBLISHED' END,
                   $START + t * interval '15 minutes', $START + t * interval '15 minutes',
                   CASE WHEN t < 33300 THEN $START + t * interval '15 minutes' + interval '1 second' END
              FROM generate_series(0, 33332) t
            """,
            "VACUUM ANALYZE observations",
            "VACUUM ANALYZE outbox",
        )
}
