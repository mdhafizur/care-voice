package com.carevoice.alert.infrastructure.persistence

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
        .append(AlertFields.OBSERVED_AT, alert.observedAt.toBsonDate())
        .append(AlertFields.CREATED_AT, alert.createdAt.toBsonDate())
        .append(AlertFields.ACKNOWLEDGED_BY, alert.acknowledgedBy)
        .append(AlertFields.ACKNOWLEDGED_AT, alert.acknowledgedAt?.toBsonDate())
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

/** Converts to a BSON date (java.util.Date), the type MongoDB uses for dates and range queries. */
internal fun Instant.toBsonDate(): Date = Date.from(this)
