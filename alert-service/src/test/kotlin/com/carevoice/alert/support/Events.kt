package com.carevoice.alert.support

import com.carevoice.contracts.ContractJson
import com.carevoice.contracts.ObservationRecordedEvent
import com.carevoice.contracts.VitalPayload
import java.util.UUID

/** Event payload factory: a fresh event per call (never mutate shared fixtures). */
object Events {
    fun event(
        eventId: String = UUID.randomUUID().toString(),
        vital: VitalPayload = VitalPayload.BloodPressure(systolic = 185, diastolic = 95),
        status: String = "CONFIRMED",
        source: String = "RULES",
        tenantId: String = TestFixtures.TENANT,
        wardId: String = TestFixtures.WARD,
        residentId: String = "res-1",
        observationId: String = "obs-$eventId".take(64),
        occurredAt: String = "2026-10-25T09:59:00Z",
        schemaVersion: Int = ObservationRecordedEvent.SCHEMA_VERSION,
    ): ObservationRecordedEvent = ObservationRecordedEvent(
        eventId = eventId,
        schemaVersion = schemaVersion,
        occurredAt = occurredAt,
        tenantId = tenantId,
        wardId = wardId,
        residentId = residentId,
        observationId = observationId,
        vital = vital,
        source = source,
        status = status,
    )

    fun json(event: ObservationRecordedEvent = event()): String =
        ContractJson.encodeToString(ObservationRecordedEvent.serializer(), event)
}
