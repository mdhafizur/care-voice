package com.carevoice.alert.infrastructure.persistence

import com.carevoice.alert.application.ProcessedEvent
import org.bson.Document

/** Field names of the `processed_events` collection. */
internal object ProcessedEventFields {
    const val COLLECTION = "processed_events"
    const val EVENT_ID = "eventId"
    const val TENANT_ID = "tenantId"
    const val WARD_ID = "wardId"
    const val OBSERVATION_ID = "observationId"
    const val ALERTS_CREATED = "alertsCreated"
    const val PROCESSED_AT = "processedAt"
    const val COMPLETED = "completed"
}

/**
 * Mapper pattern, like [AlertDocumentMapper]: explicit [ProcessedEvent] ↔ BSON document functions for the
 * `processed_events` collection.
 */
internal object ProcessedEventDocumentMapper {
    /** Stores the processed event with `processedAt` as a BSON date, which the TTL index needs. */
    fun toDocument(event: ProcessedEvent): Document = Document()
        .append(ProcessedEventFields.EVENT_ID, event.eventId)
        .append(ProcessedEventFields.TENANT_ID, event.tenantId)
        .append(ProcessedEventFields.WARD_ID, event.wardId)
        .append(ProcessedEventFields.OBSERVATION_ID, event.observationId)
        .append(ProcessedEventFields.ALERTS_CREATED, event.alertsCreated)
        .append(ProcessedEventFields.PROCESSED_AT, event.processedAt.toBsonDate())
        .append(ProcessedEventFields.COMPLETED, event.completed)

    /** Reads a processed event back. A record without `completed` counts as completed. */
    fun fromDocument(doc: Document): ProcessedEvent = ProcessedEvent(
        eventId = doc.getString(ProcessedEventFields.EVENT_ID),
        tenantId = doc.getString(ProcessedEventFields.TENANT_ID),
        wardId = doc.getString(ProcessedEventFields.WARD_ID),
        observationId = doc.getString(ProcessedEventFields.OBSERVATION_ID),
        alertsCreated = (doc.get(ProcessedEventFields.ALERTS_CREATED) as Number).toInt(),
        processedAt = doc.getDate(ProcessedEventFields.PROCESSED_AT).toInstant(),
        completed = doc.getBoolean(ProcessedEventFields.COMPLETED, true),
    )
}
