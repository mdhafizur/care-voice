package com.carevoice.alert.infrastructure.migrations

import com.carevoice.alert.infrastructure.persistence.AlertFields
import com.carevoice.alert.infrastructure.persistence.ProcessedEventFields
import com.carevoice.alert.infrastructure.persistence.ProtocolChunkFields
import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.toList
import org.bson.Document
import java.util.concurrent.TimeUnit

/**
 * Command pattern: each schema change is an object with a version, a description and an [apply] method. The runner
 * executes pending commands in order and records each one in `schema_migrations`, which gives an auditable history,
 * "apply once" semantics across pods and a way to detect a database newer than the code.
 *
 * Rules: never edit an applied migration (its [checksum] would change and startup fails); add a new one instead.
 * [apply] must be idempotent, because a pod may crash after applying and before recording.
 */
interface MongoMigration {
    val version: Int
    val description: String

    /**
     * Stable fingerprint of what the migration does. Derived from [definition] so a change to an applied migration is
     * detected at startup.
     */
    val checksum: String get() = sha256(definition)

    /** Canonical, human-readable description of the change, hashed into [checksum]. */
    val definition: String

    /** Makes the change. Must be idempotent (running it twice has the same effect as once). */
    suspend fun apply(database: MongoDatabase)
}

/**
 * Alerts: unique `eventId + rule` (a redelivered event can never create a second alert for the same rule — the
 * database, not application code, enforces it) and the compound list index whose prefix `tenantId, wardId` is the
 * ward scope every query starts with, followed by the optional status filter and the keyset sort order.
 */
object M001CreateAlertIndexes : MongoMigration {
    override val version = 1
    override val description = "Create alert indexes"

    private val indexes =
        listOf(
            IndexModel(
                Indexes.ascending(AlertFields.EVENT_ID, AlertFields.RULE),
                IndexOptions().name("ux_alerts_event_rule").unique(true),
            ),
            IndexModel(
                Indexes.compoundIndex(
                    Indexes.ascending(AlertFields.TENANT_ID, AlertFields.WARD_ID, AlertFields.STATUS),
                    Indexes.descending(AlertFields.CREATED_AT, AlertFields.ID),
                ),
                IndexOptions().name("ix_alerts_scope_status_created"),
            ),
            // Listing without a status filter would otherwise scan every status branch of the index above.
            IndexModel(
                Indexes.compoundIndex(
                    Indexes.ascending(AlertFields.TENANT_ID, AlertFields.WARD_ID),
                    Indexes.descending(AlertFields.CREATED_AT, AlertFields.ID),
                ),
                IndexOptions().name("ix_alerts_scope_created"),
            ),
        )

    override val definition: String =
        "alerts: unique {eventId:1, rule:1}; {tenantId:1, wardId:1, status:1, createdAt:-1, _id:-1}; " +
            "{tenantId:1, wardId:1, createdAt:-1, _id:-1}"

    /**
     * Creates the alerts collection if needed, then its indexes.
     * Creating an index that already exists with the same options changes nothing.
     */
    override suspend fun apply(database: MongoDatabase) {
        createCollectionIfMissing(database, AlertFields.COLLECTION)
        database.getCollection(AlertFields.COLLECTION, Document::class.java).createIndexes(indexes).toList()
    }
}

/**
 * Processed events: unique `eventId` (the idempotency decision) and a TTL index that lets MongoDB delete records
 * 30 days after `processedAt`. A TTL index needs no scheduled cleanup job, so nothing has to coordinate which pod runs
 * it; 30 days is far longer than any broker redelivery or outbox retry window.
 */
object M002CreateProcessedEventsIndexes : MongoMigration {
    override val version = 2
    override val description = "Create processed_events indexes (unique eventId, TTL 30 days)"
    const val RETENTION_DAYS = 30L

    override val definition: String =
        "processed_events: unique {eventId:1}; TTL {processedAt:1} expireAfter=${RETENTION_DAYS}d"

    /**
     * Creates the processed_events collection if needed, then its two indexes:
     * the unique one and the TTL one (TTL: MongoDB deletes the record after a set time).
     */
    override suspend fun apply(database: MongoDatabase) {
        createCollectionIfMissing(database, ProcessedEventFields.COLLECTION)
        database.getCollection(ProcessedEventFields.COLLECTION, Document::class.java).createIndexes(
            listOf(
                IndexModel(
                    Indexes.ascending(ProcessedEventFields.EVENT_ID),
                    IndexOptions().name("ux_processed_events_event").unique(true),
                ),
                IndexModel(
                    Indexes.ascending(ProcessedEventFields.PROCESSED_AT),
                    IndexOptions().name("ttl_processed_events_processed_at").expireAfter(RETENTION_DAYS, TimeUnit.DAYS),
                ),
            ),
        ).toList()
    }
}

/**
 * Protocol knowledge base (guidance RAG, ADR-0023): chunks are read per embedding model in document order, and the
 * corpus collection holds one version record per model (its `_id`, no extra index needed).
 */
object M003CreateProtocolChunkIndexes : MongoMigration {
    override val version = 3
    override val description = "Create protocol_chunks index and protocol_corpora collection"

    override val definition: String = "protocol_chunks: {model:1, order:1}; protocol_corpora: collection"

    /** Creates both protocol collections if needed, then the index on model and order. */
    override suspend fun apply(database: MongoDatabase) {
        createCollectionIfMissing(database, ProtocolChunkFields.COLLECTION)
        createCollectionIfMissing(database, ProtocolChunkFields.CORPUS_COLLECTION)
        database.getCollection(ProtocolChunkFields.COLLECTION, Document::class.java).createIndexes(
            listOf(
                IndexModel(
                    Indexes.ascending(ProtocolChunkFields.MODEL, ProtocolChunkFields.ORDER),
                    IndexOptions().name("ix_protocol_chunks_model_order"),
                ),
            ),
        ).toList()
    }
}

/** The ordered migration catalogue. Append only. */
object Migrations {
    val ALL: List<MongoMigration> =
        listOf(M001CreateAlertIndexes, M002CreateProcessedEventsIndexes, M003CreateProtocolChunkIndexes)
}

/** Creates the collection only when it does not exist yet; another pod creating it at the same time is fine. */
private suspend fun createCollectionIfMissing(database: MongoDatabase, name: String) {
    val existing = mutableListOf<String>()
    database.listCollectionNames().collect { existing += it }
    if (name !in existing) {
        try {
            database.createCollection(name)
        } catch (e: com.mongodb.MongoCommandException) {
            // 48 = NamespaceExists: another pod created it between our check and our create; that is the goal anyway.
            if (e.errorCode != NAMESPACE_EXISTS) throw e
        }
    }
}

private const val NAMESPACE_EXISTS = 48

/** SHA-256 hash of [text] as a lowercase hex string; used for migration checksums. */
internal fun sha256(text: String): String = java.security.MessageDigest
    .getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
