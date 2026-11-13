package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.ObservationRecord
import com.carevoice.documentation.infrastructure.serialization.orThrow
import com.carevoice.domain.model.BloodPressure
import com.carevoice.domain.model.BodyTemperature
import com.carevoice.domain.model.Confidence
import com.carevoice.domain.model.ExtractionSource
import com.carevoice.domain.model.Observation
import com.carevoice.domain.model.ObservationId
import com.carevoice.domain.model.ObservationStatus
import com.carevoice.domain.model.Pulse
import com.carevoice.domain.model.ResidentId
import com.carevoice.domain.model.VitalSign
import com.carevoice.domain.model.VitalType
import java.math.BigDecimal
import java.sql.ResultSet

/**
 * Mapper pattern: turns an [ObservationRecord] into the values of one `observations` row, and a row back into a
 * record. [PostgresObservationRepository] runs the SQL; this object only knows the columns.
 */
internal object ObservationRows {
    /** Every column of `observations`, in the order [insertParams] returns the values. */
    const val COLUMNS =
        "id, tenant_id, ward_id, resident_id, transcript_id, vital_type, systolic, diastolic, pulse_bpm, " +
            "temperature_celsius, source, confidence, status, recorded_at, confirmed_by, confirmed_at, version"

    /** The insert values in [COLUMNS] order; columns of the other vital types stay null. */
    fun insertParams(record: ObservationRecord): List<Any?> {
        val vital = record.observation.vital
        return listOf(
            record.id,
            record.tenantId,
            record.wardId,
            record.observation.residentId.value,
            record.transcriptId,
            vital.type.name,
            (vital as? BloodPressure)?.systolic,
            (vital as? BloodPressure)?.diastolic,
            (vital as? Pulse)?.bpm,
            (vital as? BodyTemperature)?.celsius?.let { BigDecimal.valueOf(it) },
            record.observation.source.name,
            BigDecimal.valueOf(record.observation.confidence.value),
            record.observation.status.name,
            record.recordedAt,
            record.confirmedBy,
            record.confirmedAt,
            record.version,
        )
    }

    /** Mapper: row → domain, through the smart constructors. */
    fun toRecord(rs: ResultSet): ObservationRecord {
        val vital: VitalSign =
            when (VitalType.valueOf(rs.getString("vital_type"))) {
                VitalType.BLOOD_PRESSURE -> BloodPressure.of(
                    rs.getInt("systolic"),
                    rs.getInt("diastolic"),
                ).orThrow("blood pressure")

                VitalType.PULSE -> Pulse.of(rs.getInt("pulse_bpm")).orThrow("pulse")

                VitalType.BODY_TEMPERATURE ->
                    BodyTemperature.ofCelsius(
                        rs.getBigDecimal("temperature_celsius").toDouble(),
                    ).orThrow("temperature")
            }
        return ObservationRecord(
            observation =
            Observation(
                id = ObservationId(rs.uuid("id")),
                residentId = ResidentId.of(rs.getString("resident_id")).orThrow("resident id"),
                vital = vital,
                recordedAt = rs.instant("recorded_at"),
                source = ExtractionSource.valueOf(rs.getString("source")),
                confidence = Confidence(rs.getBigDecimal("confidence").toDouble()),
                status = ObservationStatus.valueOf(rs.getString("status")),
            ),
            tenantId = rs.getString("tenant_id"),
            wardId = rs.getString("ward_id"),
            transcriptId = rs.uuid("transcript_id"),
            confirmedBy = rs.getString("confirmed_by"),
            confirmedAt = rs.instantOrNull("confirmed_at"),
            version = rs.getInt("version"),
        )
    }
}
