package com.carevoice.alert.infrastructure.persistence

import com.carevoice.alert.application.Keyset
import com.carevoice.alert.domain.AlertStatus
import com.carevoice.alert.domain.WardScope
import com.carevoice.alert.support.TestMongo
import com.carevoice.testsupport.TestDatabases
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plan regression tests for the alert lists (docs/indexing.md): the `find` commands [MongoAlertRepository] really
 * sends are captured with a [CommandListener] and run again through `explain` (executionStats), so the tests assert
 * on the repository's query, not on a copy. What is asserted is the access path (which index, no collection scan, no
 * in-memory SORT) and how much of it is read (ESR: the documents examined are the documents returned).
 *
 * Seeded with production's shape at a small scale: 30k alerts in 50 wards, 3 % still OPEN.
 */
@DisplayName("Alert query plans")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlertQueryPlanTest {
    private val sentFinds = CopyOnWriteArrayList<BsonDocument>()
    private val client =
        MongoClient.create(
            MongoClientSettings
                .builder()
                .applyConnectionString(ConnectionString(TestDatabases.mongo.connectionString))
                .addCommandListener(
                    object : CommandListener {
                        override fun commandStarted(event: CommandStartedEvent) {
                            if (event.commandName == "find") sentFinds += event.command.clone()
                        }
                    },
                ).build(),
        )

    // Migrated through the shared test client (indexes exactly as production creates them), used through ours.
    private val database: MongoDatabase = client.getDatabase(TestMongo.freshDatabase("AlertQueryPlanTest").name)
    private val repository = MongoAlertRepository(database)

    @BeforeAll
    fun seed() = runBlocking<Unit> {
        val alerts = database.getCollection(AlertFields.COLLECTION, Document::class.java)
        (0 until ALERTS).chunked(5_000).forEach { batch -> alerts.insertMany(batch.map(::alertDocument)) }
    }

    @AfterAll
    fun close() {
        client.close()
    }

    @BeforeEach
    fun forgetEarlierCommands() {
        sentFinds.clear()
    }

    @Nested
    @DisplayName("Open alerts (GET /v1/alerts?status=OPEN)")
    inner class OpenAlerts {
        @Test
        fun `should read only the open alerts of the ward, already in order`() = runBlocking<Unit> {
            repository.findPage(SCOPE, AlertStatus.OPEN, keyset = null, limit = 21)

            val plan = explainLastFind()
            plan.assertServedBy("ix_alerts_scope_status_created")
            // Equality, Equality, Sort: status sits before the sort keys, so no closed alert is ever fetched.
            assertThat(plan.docsExamined).describedAs("documents examined by\n$plan").isEqualTo(plan.returned)
        }
    }

    @Nested
    @DisplayName("All alerts (GET /v1/alerts)")
    inner class AllAlerts {
        @Test
        fun `should start a deep keyset page at the cursor instead of reading from the newest alert`() =
            runBlocking<Unit> {
                val cursor = cursorAt(position = 300)

                repository.findPage(SCOPE, status = null, keyset = cursor, limit = 21)

                val plan = explainLastFind()
                plan.assertServedBy("ix_alerts_scope_created")
                // 300 alerts lie before the cursor; reading them would show up as keys examined.
                assertThat(plan.keysExamined).describedAs("keys examined by\n$plan").isLessThan(60)
            }
    }

    private suspend fun cursorAt(position: Int): Keyset {
        val alert =
            database
                .getCollection(AlertFields.COLLECTION, Document::class.java)
                .find(Filters.and(Filters.eq(AlertFields.TENANT_ID, TENANT), Filters.eq(AlertFields.WARD_ID, WARD)))
                .sort(Sorts.descending(AlertFields.CREATED_AT, AlertFields.ID))
                .skip(position)
                .limit(1)
                .first()
        return Keyset(alert.getDate(AlertFields.CREATED_AT).toInstant(), alert.getString(AlertFields.ID), OLDER)
    }

    /** Re-runs the repository's last `find` (session and cluster fields removed) as `explain` with executionStats. */
    private suspend fun explainLastFind(): MongoPlan {
        val sent = checkNotNull(sentFinds.lastOrNull()) { "the repository sent no find command" }
        val find = BsonDocument()
        sent.forEach { (key, value) -> if (!key.startsWith("$") && key !in SESSION_FIELDS) find[key] = value }
        return MongoPlan(
            find.toJson(),
            database.runCommand(BsonDocument("explain", find).append("verbosity", BsonString("executionStats"))),
        )
    }

    private fun MongoPlan.assertServedBy(index: String) {
        assertThat(indexes).describedAs("indexes read by\n$this").contains(index)
        assertThat(stages).describedAs("stages of\n$this").doesNotContain("COLLSCAN", "SORT")
    }

    private companion object {
        const val ALERTS = 30_000
        const val TENANT = "plan-tenant-0"
        const val WARD = "plan-ward-00"
        val SCOPE = WardScope(TENANT, WARD)
        val OLDER = Keyset.Direction.OLDER
        val SESSION_FIELDS = setOf("lsid", "txnNumber", "autocommit", "startTransaction")
        val START: Instant = Instant.parse("2025-10-01T00:00:00Z")

        // Same generator as scripts/db-bench/mongo.js: resident r = i * 7919 mod 2500 lives in ward r / 50.
        fun alertDocument(i: Int): Document {
            val resident = (i.toLong() * 7919 % 2500).toInt()
            val open = i.toLong() * 2654435761 % 100 < 3
            val createdAt = Date.from(START.plusSeconds(i * 1_000L))
            return Document()
                .append(AlertFields.ID, "plan-alert-$i")
                .append(AlertFields.TENANT_ID, "plan-tenant-${resident / 500}")
                .append(AlertFields.WARD_ID, "plan-ward-%02d".format(resident / 50))
                .append(AlertFields.RESIDENT_ID, "plan-resident-$resident")
                .append(AlertFields.OBSERVATION_ID, "plan-observation-$i")
                .append(AlertFields.EVENT_ID, "plan-event-$i")
                .append(AlertFields.RULE, "TACHYCARDIA")
                .append(AlertFields.SEVERITY, "WARNING")
                .append(AlertFields.MESSAGE, "Pulse 132 bpm is above 120 bpm")
                .append(AlertFields.VITAL, Document("type", "PULSE").append("bpm", 132))
                .append(AlertFields.STATUS, if (open) "OPEN" else "ACKNOWLEDGED")
                .append(AlertFields.OBSERVED_AT, createdAt)
                .append(AlertFields.CREATED_AT, createdAt)
                .append(AlertFields.VERSION, if (open) 0L else 1L)
        }
    }
}

/** One `explain` result: the winning plan flattened into its stages, plus the execution counters. */
private class MongoPlan(private val command: String, explain: Document) {
    private val root: Document =
        explain.get("queryPlanner", Document::class.java).get("winningPlan", Document::class.java).let {
            // The slot-based engine (MongoDB 7+) nests the logical plan under queryPlan.
            it.get("queryPlan", Document::class.java) ?: it
        }
    private val stats: Document = explain.get("executionStats", Document::class.java)

    val nodes: List<Document> = flatten(root)
    val stages: List<String> = nodes.map { it.getString("stage") }
    val indexes: Set<String> = nodes.mapNotNull { it.getString("indexName") }.toSet()
    val returned: Long = stats.count("nReturned")
    val keysExamined: Long = stats.count("totalKeysExamined")
    val docsExamined: Long = stats.count("totalDocsExamined")

    override fun toString(): String = buildString {
        appendLine(command)
        nodes.forEach { node ->
            append("  ").append(node.getString("stage"))
            node.getString("indexName")?.let { append(" · ").append(it) }
            appendLine()
        }
        append("  returned=$returned keysExamined=$keysExamined docsExamined=$docsExamined")
    }

    private companion object {
        fun flatten(node: Document): List<Document> {
            val children =
                node.get("inputStage", Document::class.java)?.let(::listOf)
                    ?: node.getList("inputStages", Document::class.java)
                    ?: emptyList()
            return listOf(node) + children.flatMap(::flatten)
        }

        fun Document.count(key: String): Long = (get(key) as Number).toLong()
    }
}
