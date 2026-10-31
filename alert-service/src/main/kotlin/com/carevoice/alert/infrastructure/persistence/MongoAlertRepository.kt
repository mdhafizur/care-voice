package com.carevoice.alert.infrastructure.persistence

import com.carevoice.alert.application.AlertRepository
import com.carevoice.alert.application.Keyset
import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.AlertStatus
import com.carevoice.alert.domain.WardScope
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import com.mongodb.MongoBulkWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Instant

/**
 * Repository pattern (adapter): [AlertRepository] on MongoDB.
 *
 * Every query starts with `tenantId + wardId`, which is both the isolation rule and the prefix of the compound index
 * `{tenantId, wardId, status, createdAt:-1, _id:-1}` created by migration M001.
 */
class MongoAlertRepository(database: MongoDatabase) : AlertRepository {
    private val log = LoggerFactory.getLogger(MongoAlertRepository::class.java)

    private val collection = database.getCollection(AlertFields.COLLECTION, Document::class.java)

    /**
     * Unordered insert: one duplicate (`eventId + rule` already stored by an earlier, interrupted attempt) must not
     * stop the others. Duplicate-key errors are expected and filtered out; anything else propagates.
     */
    override suspend fun insertAll(alerts: List<Alert>): List<Alert> {
        if (alerts.isEmpty()) return emptyList()
        return MongoErrors.translate {
            try {
                collection.insertMany(alerts.map(AlertDocumentMapper::toDocument), InsertManyOptions().ordered(false))
                alerts
            } catch (e: MongoBulkWriteException) {
                if (!MongoErrors.onlyDuplicateKeys(e)) throw e
                val failed = e.writeErrors.map { it.index }.toSet()
                // Redelivered event: the unique {eventId, rule} index rejected the alerts that already exist.
                log.event(
                    Level.DEBUG,
                    LogType.DB_OPERATION,
                    "Duplicate alerts ignored",
                    "collection" to "alerts",
                    "operation" to "write",
                    "duplicates" to failed.size,
                    "inserted" to alerts.size - failed.size,
                )
                alerts.filterIndexed { index, _ -> index !in failed }
            }
        }
    }

    override suspend fun findById(scope: WardScope, alertId: String): Alert? = MongoErrors.translate {
        collection
            .find(Filters.and(scopeFilter(scope), Filters.eq(AlertFields.ID, alertId)))
            .limit(1)
            .map(AlertDocumentMapper::fromDocument)
            .firstOrNull()
    }

    override suspend fun findPage(scope: WardScope, status: AlertStatus?, keyset: Keyset?, limit: Int): List<Alert> {
        val filters = mutableListOf(scopeFilter(scope))
        status?.let { filters += Filters.eq(AlertFields.STATUS, it.name) }
        keyset?.let { filters += keysetFilter(it) }
        val sort =
            if (keyset?.direction == Keyset.Direction.NEWER) {
                Sorts.ascending(AlertFields.CREATED_AT, AlertFields.ID)
            } else {
                Sorts.descending(AlertFields.CREATED_AT, AlertFields.ID)
            }
        return MongoErrors.translate {
            collection
                .find(Filters.and(filters))
                .sort(sort)
                .limit(limit)
                .map(AlertDocumentMapper::fromDocument)
                .toList()
        }
    }

    override suspend fun count(scope: WardScope, status: AlertStatus?): Long {
        val filter =
            if (status == null) {
                scopeFilter(scope)
            } else {
                Filters.and(scopeFilter(scope), Filters.eq(AlertFields.STATUS, status.name))
            }
        return MongoErrors.translate { collection.countDocuments(filter) }
    }

    /**
     * One atomic conditional update (optimistic locking): matches only while the alert is still OPEN at the expected
     * version, so concurrent acknowledgements cannot overwrite each other.
     */
    override suspend fun acknowledge(
        scope: WardScope,
        alertId: String,
        expectedVersion: Long,
        acknowledgedBy: String,
        acknowledgedAt: Instant,
    ): Alert? {
        val filter =
            Filters.and(
                scopeFilter(scope),
                Filters.eq(AlertFields.ID, alertId),
                Filters.eq(AlertFields.STATUS, AlertStatus.OPEN.name),
                Filters.eq(AlertFields.VERSION, expectedVersion),
            )
        val update =
            Updates.combine(
                Updates.set(AlertFields.STATUS, AlertStatus.ACKNOWLEDGED.name),
                Updates.set(AlertFields.ACKNOWLEDGED_BY, acknowledgedBy),
                Updates.set(AlertFields.ACKNOWLEDGED_AT, acknowledgedAt.toBsonDate()),
                Updates.inc(AlertFields.VERSION, 1L),
            )
        return MongoErrors.translate {
            collection
                .findOneAndUpdate(filter, update, FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER))
                ?.let(AlertDocumentMapper::fromDocument)
        }.also {
            log.event(
                Level.DEBUG,
                LogType.DB_OPERATION,
                "Conditional acknowledge update",
                "collection" to "alerts",
                "operation" to "update",
                "alertId" to alertId,
                "expectedVersion" to expectedVersion,
                "matched" to (it != null),
            )
        }
    }

    private fun scopeFilter(scope: WardScope): Bson =
        Filters.and(Filters.eq(AlertFields.TENANT_ID, scope.tenantId), Filters.eq(AlertFields.WARD_ID, scope.wardId))

    /** `(createdAt, _id) < (k.createdAt, k.id)` for OLDER, `>` for NEWER; spelled out as Mongo has no row compare. */
    private fun keysetFilter(keyset: Keyset): Bson {
        val createdAt = keyset.createdAt.toBsonDate()
        return if (keyset.direction == Keyset.Direction.OLDER) {
            Filters.or(
                Filters.lt(AlertFields.CREATED_AT, createdAt),
                Filters.and(Filters.eq(AlertFields.CREATED_AT, createdAt), Filters.lt(AlertFields.ID, keyset.id)),
            )
        } else {
            Filters.or(
                Filters.gt(AlertFields.CREATED_AT, createdAt),
                Filters.and(Filters.eq(AlertFields.CREATED_AT, createdAt), Filters.gt(AlertFields.ID, keyset.id)),
            )
        }
    }
}
