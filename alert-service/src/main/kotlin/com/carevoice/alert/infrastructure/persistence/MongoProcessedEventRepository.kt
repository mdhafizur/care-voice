package com.carevoice.alert.infrastructure.persistence

import com.carevoice.alert.application.ProcessedEvent
import com.carevoice.alert.application.ProcessedEventRepository
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.bson.Document
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Repository pattern (adapter): [ProcessedEventRepository] on MongoDB.
 *
 * Duplicate detection is the unique index on `eventId` (migration M002): insert and catch the duplicate-key error.
 * A "find, then insert" sequence would let two pods both see "absent" and both process the event. The TTL index on
 * `processedAt` deletes records after 30 days, far beyond any redelivery window, so the collection stays small
 * without a cleanup job (and without a job that must run on exactly one pod).
 */
class MongoProcessedEventRepository(database: MongoDatabase) : ProcessedEventRepository {
    private val log = LoggerFactory.getLogger(MongoProcessedEventRepository::class.java)

    private val collection = database.getCollection(ProcessedEventFields.COLLECTION, Document::class.java)

    override suspend fun tryInsert(event: ProcessedEvent): Boolean = MongoErrors.translate {
        try {
            collection.insertOne(ProcessedEventDocumentMapper.toDocument(event))
            true
        } catch (e: MongoWriteException) {
            if (!MongoErrors.isDuplicateKey(e)) throw e
            // The unique eventId index decided: another delivery (or pod) already claimed this event.
            log.event(
                Level.DEBUG,
                LogType.DB_OPERATION,
                "Processed-event record exists",
                "collection" to "processed_events",
                "operation" to "write",
                "eventId" to event.eventId,
            )
            false
        }
    }

    override suspend fun find(eventId: String): ProcessedEvent? = MongoErrors.translate {
        collection
            .find(Filters.eq(ProcessedEventFields.EVENT_ID, eventId))
            .limit(1)
            .map(ProcessedEventDocumentMapper::fromDocument)
            .firstOrNull()
    }

    override suspend fun markCompleted(eventId: String) {
        MongoErrors.translate {
            collection.updateOne(
                Filters.eq(ProcessedEventFields.EVENT_ID, eventId),
                Updates.set(ProcessedEventFields.COMPLETED, true),
            )
        }
    }
}
