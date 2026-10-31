package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.ConfirmOutcome
import com.carevoice.documentation.application.ObservationFilter
import com.carevoice.documentation.application.ObservationRecord
import com.carevoice.documentation.application.ObservationRepository
import com.carevoice.documentation.application.Page
import com.carevoice.documentation.application.PageCursor
import com.carevoice.documentation.application.PageRequest
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
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Repository pattern: observations in PostgreSQL.
 *
 * Keyset pagination (docs/CONTRACTS.md, section 14) on `(recorded_at, id)`, both DESC, served by the indexes
 * `observations_resident_recorded_idx` and `observations_ward_recorded_idx`:
 * - next page: `WHERE (recorded_at, id) < (?, ?) ORDER BY recorded_at DESC, id DESC LIMIT limit + 1`
 * - previous page: the mirrored ascending query `> (?, ?) ORDER BY ... ASC`, reversed in memory
 * The extra row only signals that another page exists. Row-value comparison keeps ties on `recorded_at` (all
 * observations of one transcript share it) strictly ordered by id, so nothing is skipped or repeated.
 *
 * Every query carries `tenant_id` AND `ward_id`: isolation is enforced in SQL, not only in the use case.
 */
class PostgresObservationRepository(private val dataSource: DataSource) : ObservationRepository {
    override suspend fun insertAll(records: List<ObservationRecord>) {
        if (records.isEmpty()) return
        withConnection(dataSource) { connection ->
            connection.prepareStatement(INSERT_SQL).use { statement ->
                records.forEach { record ->
                    statement.bindAll(insertParams(record))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    override suspend fun findById(tenantId: String, wardId: String, observationId: UUID): ObservationRecord? =
        withConnection(dataSource) { connection ->
            connection.queryOne(
                "$SELECT_SQL WHERE tenant_id = ? AND ward_id = ? AND id = ?",
                listOf(tenantId, wardId, observationId),
                ::toRecord,
            )
        }

    override suspend fun listByResident(
        tenantId: String,
        wardId: String,
        residentId: String,
        page: PageRequest,
    ): Page<ObservationRecord> = withConnection(dataSource) { connection ->
        keysetPage(
            connection,
            Where(listOf("tenant_id = ?", "ward_id = ?", "resident_id = ?"), listOf(tenantId, wardId, residentId)),
            page,
        )
    }

    override suspend fun query(
        tenantId: String,
        wardId: String,
        filter: ObservationFilter,
        page: PageRequest,
    ): Page<ObservationRecord> = withConnection(dataSource) { connection ->
        val conditions = mutableListOf("tenant_id = ?", "ward_id = ?")
        val params = mutableListOf<Any?>(tenantId, wardId)
        if (filter.residentIds.isNotEmpty()) {
            conditions += "resident_id = ANY(?)"
            params += TextArray(filter.residentIds)
        }
        if (filter.vitalTypes.isNotEmpty()) {
            conditions += "vital_type = ANY(?)"
            params += TextArray(filter.vitalTypes.map { it.name })
        }
        filter.status?.let {
            conditions += "status = ?"
            params += it.name
        }
        filter.recordedFrom?.let {
            conditions += "recorded_at >= ?"
            params += it
        }
        filter.recordedTo?.let {
            conditions += "recorded_at <= ?"
            params += it
        }
        keysetPage(connection, Where(conditions, params), page)
    }

    override suspend fun confirm(
        tenantId: String,
        wardId: String,
        observationId: UUID,
        expectedVersion: Int,
        confirmedBy: String,
        confirmedAt: Instant,
    ): ConfirmOutcome = withConnection(dataSource) { connection ->
        val updated =
            connection.queryOne(
                """
                    UPDATE observations
                       SET status = 'CONFIRMED', confirmed_by = ?, confirmed_at = ?, version = version + 1
                     WHERE tenant_id = ? AND ward_id = ? AND id = ? AND version = ?
                    RETURNING $COLUMNS
                    """,
                listOf(confirmedBy, confirmedAt, tenantId, wardId, observationId, expectedVersion),
                ::toRecord,
            )
        when {
            updated != null -> ConfirmOutcome.Confirmed(updated)

            connection.queryOne(
                "SELECT 1 FROM observations WHERE tenant_id = ? AND ward_id = ? AND id = ?",
                listOf(tenantId, wardId, observationId),
            ) { true } != null -> ConfirmOutcome.VersionMismatch

            else -> ConfirmOutcome.NotFound
        }
    }

    override suspend fun findAfter(
        tenantId: String,
        wardId: String,
        afterObservationId: UUID,
        limit: Int,
    ): List<ObservationRecord> = withConnection(dataSource) { connection ->
        connection.queryList(
            """
                $SELECT_SQL
                 WHERE tenant_id = ? AND ward_id = ?
                   AND (recorded_at, id) > (SELECT recorded_at, id FROM observations
                                             WHERE tenant_id = ? AND ward_id = ? AND id = ?)
                 ORDER BY recorded_at ASC, id ASC
                 LIMIT ?
                """,
            listOf(tenantId, wardId, tenantId, wardId, afterObservationId, limit),
            ::toRecord,
        )
    }

    private data class Where(val conditions: List<String>, val params: List<Any?>) {
        val sql: String get() = conditions.joinToString(" AND ")
    }

    private fun keysetPage(connection: Connection, where: Where, page: PageRequest): Page<ObservationRecord> {
        val total =
            connection.queryOne("SELECT count(*) FROM observations WHERE ${where.sql}", where.params) { it.getLong(1) }
                ?: 0L
        // A well-formed cursor that points at no row in this scope (deleted, other ward, forged) → first page.
        val cursor = page.cursor?.takeIf { cursorExists(connection, where, it) }
        val fetch = page.limit + 1

        if (cursor == null || cursor.direction == PageCursor.Direction.AFTER) {
            val conditions = if (cursor == null) where.sql else "${where.sql} AND (recorded_at, id) < (?, ?)"
            val params = if (cursor == null) where.params else where.params + listOf(cursor.recordedAt, cursor.id)
            val rows =
                connection.queryList(
                    "$SELECT_SQL WHERE $conditions ORDER BY recorded_at DESC, id DESC LIMIT ?",
                    params + fetch,
                    ::toRecord,
                )
            val items = rows.take(page.limit)
            return Page(
                items = items,
                nextCursor = if (rows.size > page.limit) PageCursor.after(items.last()).encode() else null,
                prevCursor = if (cursor != null &&
                    items.isNotEmpty()
                ) {
                    PageCursor.before(items.first()).encode()
                } else {
                    null
                },
                totalCount = total,
            )
        }

        val rows =
            connection.queryList(
                "$SELECT_SQL WHERE ${where.sql} AND (recorded_at, id) > (?, ?) ORDER BY recorded_at ASC, id ASC LIMIT ?",
                where.params + listOf(cursor.recordedAt, cursor.id, fetch),
                ::toRecord,
            )
        val items = rows.take(page.limit).reversed()
        return Page(
            items = items,
            // The cursor row itself is older than every item, so a next page always exists.
            nextCursor = items.lastOrNull()?.let { PageCursor.after(it).encode() },
            prevCursor = if (rows.size > page.limit) PageCursor.before(items.first()).encode() else null,
            totalCount = total,
        )
    }

    private fun cursorExists(connection: Connection, where: Where, cursor: PageCursor): Boolean = connection.queryOne(
        "SELECT 1 FROM observations WHERE ${where.sql} AND id = ? AND recorded_at = ?",
        where.params + listOf(cursor.id, cursor.recordedAt),
    ) { true } == true

    companion object {
        internal const val COLUMNS =
            "id, tenant_id, ward_id, resident_id, transcript_id, vital_type, systolic, diastolic, pulse_bpm, " +
                "temperature_celsius, source, confidence, status, recorded_at, confirmed_by, confirmed_at, version"
        private const val SELECT_SQL = "SELECT $COLUMNS FROM observations"
        private const val INSERT_SQL =
            "INSERT INTO observations ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        private fun insertParams(record: ObservationRecord): List<Any?> {
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
        internal fun toRecord(rs: ResultSet): ObservationRecord {
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
}
