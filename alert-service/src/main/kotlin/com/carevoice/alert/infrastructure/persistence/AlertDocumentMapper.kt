package com.carevoice.alert.infrastructure.persistence

import com.carevoice.alert.application.ProcessedEvent
import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.AlertRuleType
import com.carevoice.alert.domain.AlertStatus
import com.carevoice.alert.domain.Severity
import com.carevoice.alert.domain.Vital
import org.bson.Document
import java.time.Instant
import java.util.Date

/** Field names of the `alerts` collection, shared by the mapper, the repository and the migrations. */
internal object AlertFields {
    const val COLLECTION = "alerts"
    const val ID = "_id"
    const val TENANT_ID = "tenantId"
    const val WARD_ID = "wardId"
    const val RESIDENT_ID = "residentId"
    const val OBSERVATION_ID = "observationId"
    const val EVENT_ID = "eventId"
    const val RULE = "rule"
    const val SEVERITY = "severity"
    const val MESSAGE = "message"
    const val VITAL = "vital"
    const val STATUS = "status"
    const val OBSERVED_AT = "observedAt"
    const val CREATED_AT = "createdAt"
    const val ACKNOWLEDGED_BY = "acknowledgedBy"
    const val ACKNOWLEDGED_AT = "acknowledgedAt"
    const val VERSION = "version"
}

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
 * Mapper pattern: explicit domain ↔ BSON document functions instead of reflection-based codecs. The stored shape is
 * visible in one file, a domain rename cannot silently change the persisted field names, and dates are stored as BSON
 * dates (required by the TTL index and by range queries).
 */
internal object AlertDocumentMapper {
    /** Enums are stored by name and times as BSON dates; the vital is a nested document with a `type` field. */
    fun toDocument(alert: Alert): Document = Document()
        .append(AlertFields.ID, alert.id)
        .append(AlertFields.TENANT_ID, alert.tenantId)
        .append(AlertFields.WARD_ID, alert.wardId)
        .append(AlertFields.RESIDENT_ID, alert.residentId)
        .append(AlertFields.OBSERVATION_ID, alert.observationId)
        .append(AlertFields.EVENT_ID, alert.eventId)
        .append(AlertFields.RULE, alert.rule.name)
        .append(AlertFields.SEVERITY, alert.severity.name)
        .append(AlertFields.MESSAGE, alert.message)
        .append(AlertFields.VITAL, vitalToDocument(alert.vital))
        .append(AlertFields.STATUS, alert.status.name)
        .append(AlertFields.OBSERVED_AT, Date.from(alert.observedAt))
        .append(AlertFields.CREATED_AT, Date.from(alert.createdAt))
        .append(AlertFields.ACKNOWLEDGED_BY, alert.acknowledgedBy)
        .append(AlertFields.ACKNOWLEDGED_AT, alert.acknowledgedAt?.let { Date.from(it) })
        .append(AlertFields.VERSION, alert.version)

    /** Reads a stored alert back. Unknown enum names or missing required fields throw. */
    fun fromDocument(doc: Document): Alert = Alert(
        id = doc.getString(AlertFields.ID),
        tenantId = doc.getString(AlertFields.TENANT_ID),
        wardId = doc.getString(AlertFields.WARD_ID),
        residentId = doc.getString(AlertFields.RESIDENT_ID),
        observationId = doc.getString(AlertFields.OBSERVATION_ID),
        eventId = doc.getString(AlertFields.EVENT_ID),
        rule = AlertRuleType.valueOf(doc.getString(AlertFields.RULE)),
        severity = Severity.valueOf(doc.getString(AlertFields.SEVERITY)),
        message = doc.getString(AlertFields.MESSAGE),
        vital = vitalFromDocument(doc.get(AlertFields.VITAL, Document::class.java)),
        status = AlertStatus.valueOf(doc.getString(AlertFields.STATUS)),
        observedAt = doc.getDate(AlertFields.OBSERVED_AT).toInstant(),
        createdAt = doc.getDate(AlertFields.CREATED_AT).toInstant(),
        acknowledgedBy = doc.getString(AlertFields.ACKNOWLEDGED_BY),
        acknowledgedAt = doc.getDate(AlertFields.ACKNOWLEDGED_AT)?.toInstant(),
        version = (doc.get(AlertFields.VERSION) as Number).toLong(),
    )

    /** Stores the vital as a nested document; `type` says which kind of vital it is. */
    fun vitalToDocument(vital: Vital): Document = when (vital) {
        is Vital.BloodPressure ->
            Document("type", "BLOOD_PRESSURE")
                .append("systolic", vital.systolic)
                .append("diastolic", vital.diastolic)

        is Vital.Pulse -> Document("type", "PULSE").append("bpm", vital.bpm)

        is Vital.BodyTemperature -> Document("type", "BODY_TEMPERATURE").append("celsius", vital.celsius)
    }

    /** Reads the nested vital by its `type` field; an unknown type throws. */
    fun vitalFromDocument(doc: Document): Vital = when (val type = doc.getString("type")) {
        "BLOOD_PRESSURE" ->
            Vital.BloodPressure((doc.get("systolic") as Number).toInt(), (doc.get("diastolic") as Number).toInt())

        "PULSE" -> Vital.Pulse((doc.get("bpm") as Number).toInt())

        "BODY_TEMPERATURE" -> Vital.BodyTemperature((doc.get("celsius") as Number).toDouble())

        else -> error("Unknown vital type '$type' in stored alert")
    }
}

internal object ProcessedEventDocumentMapper {
    /** Stores the processed event with `processedAt` as a BSON date, which the TTL index needs. */
    fun toDocument(event: ProcessedEvent): Document = Document()
        .append(ProcessedEventFields.EVENT_ID, event.eventId)
        .append(ProcessedEventFields.TENANT_ID, event.tenantId)
        .append(ProcessedEventFields.WARD_ID, event.wardId)
        .append(ProcessedEventFields.OBSERVATION_ID, event.observationId)
        .append(ProcessedEventFields.ALERTS_CREATED, event.alertsCreated)
        .append(ProcessedEventFields.PROCESSED_AT, Date.from(event.processedAt))
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

/** Converts to a BSON date (java.util.Date), the type MongoDB uses for dates and range queries. */
internal fun Instant.toBsonDate(): Date = Date.from(this)
