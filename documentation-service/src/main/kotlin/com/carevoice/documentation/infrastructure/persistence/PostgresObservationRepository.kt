package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.ConfirmOutcome
import com.carevoice.documentation.application.ObservationFilter
import com.carevoice.documentation.application.ObservationRecord
import com.carevoice.documentation.application.ObservationRepository
import com.carevoice.documentation.application.Page
import com.carevoice.documentation.application.PageCursor
import com.carevoice.documentation.application.PageRequest
import java.sql.Connection
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
    /** Inserts all records in one JDBC batch; an empty list does nothing. */
    override suspend fun insertAll(records: List<ObservationRecord>) {
        if (records.isEmpty()) return
        withConnection(dataSource) { connection ->
            connection.prepareStatement(INSERT_SQL).use { statement ->
                records.forEach { record ->
                    statement.bindAll(ObservationRows.insertParams(record))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    /** Finds one observation, only inside the given tenant and ward. */
    override suspend fun findById(tenantId: String, wardId: String, observationId: UUID): ObservationRecord? =
        withReadConnection(dataSource) { connection ->
            connection.queryOne(
                "$SELECT_SQL WHERE tenant_id = ? AND ward_id = ? AND id = ?",
                listOf(tenantId, wardId, observationId),
                ObservationRows::toRecord,
            )
        }

    /** One keyset page of a resident's observations, newest first. */
    override suspend fun listByResident(
        tenantId: String,
        wardId: String,
        residentId: String,
        page: PageRequest,
    ): Page<ObservationRecord> = withReadConnection(dataSource) { connection ->
        keysetPage(
            connection,
            Where(listOf("tenant_id = ?", "ward_id = ?", "resident_id = ?"), listOf(tenantId, wardId, residentId)),
            page,
        )
    }

    /**
     * One keyset page of the ward's observations, newest first.
     * Each filter field that is set adds one condition; resident ids and vital types match any value in the list.
     */
    override suspend fun query(
        tenantId: String,
        wardId: String,
        filter: ObservationFilter,
        page: PageRequest,
    ): Page<ObservationRecord> = withReadConnection(dataSource) { connection ->
        // Step 1: every query stays inside the caller's tenant (care provider) and ward.
        val conditions = mutableListOf("tenant_id = ?", "ward_id = ?")
        val params = mutableListOf<Any?>(tenantId, wardId)
        // Step 2: add the filters. `= ANY(?)` matches any value of a list sent as one array parameter.
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
        // Step 3: read one keyset page (paging from the last row seen, not by an offset).
        keysetPage(connection, Where(conditions, params), page)
    }

    /**
     * Confirms the observation only when its version still equals [expectedVersion] (optimistic locking: the update
     * fails instead of overwriting a change someone else made). A row that exists with another version gives
     * VersionMismatch; no row gives NotFound.
     */
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
                    RETURNING ${ObservationRows.COLUMNS}
                    """,
                listOf(confirmedBy, confirmedAt, tenantId, wardId, observationId, expectedVersion),
                ObservationRows::toRecord,
            )
        when {
            updated != null -> ConfirmOutcome.Confirmed(updated)
            observationExists(connection, tenantId, wardId, observationId) -> ConfirmOutcome.VersionMismatch
            else -> ConfirmOutcome.NotFound
        }
    }

    /**
     * Up to [limit] observations recorded after the given one, oldest first.
     * Returns an empty list when that observation is not in this tenant and ward.
     */
    override suspend fun findAfter(
        tenantId: String,
        wardId: String,
        afterObservationId: UUID,
        limit: Int,
    ): List<ObservationRecord> = withReadConnection(dataSource) { connection ->
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
            ObservationRows::toRecord,
        )
    }

    private data class Where(val conditions: List<String>, val params: List<Any?>) {
        val sql: String get() = conditions.joinToString(" AND ")
    }

    /**
     * Runs the keyset query for [page] and adds the total count and the next and previous cursors.
     * A cursor whose row is not in this scope is ignored, so the first page is returned.
     */
    private fun keysetPage(connection: Connection, where: Where, page: PageRequest): Page<ObservationRecord> {
        // Step 1: count all rows that match, for the total count.
        val total =
            connection.queryOne("SELECT count(*) FROM observations WHERE ${where.sql}", where.params) { it.getLong(1) }
                ?: 0L
        // Step 2: check the cursor (the marker of where the last page ended), and ask for one row more than
        // the page size, so we know whether a further page exists.
        // A well-formed cursor that points at no row in this scope (deleted, other ward, forged) → first page.
        val cursor = page.cursor?.takeIf { cursorExists(connection, where, it) }
        val fetch = page.limit + 1

        // Step 3: first page or going forward: newest first, older than the cursor row.
        if (cursor == null || cursor.direction == PageCursor.Direction.AFTER) {
            val conditions = if (cursor == null) where.sql else "${where.sql} AND (recorded_at, id) < (?, ?)"
            val params = if (cursor == null) where.params else where.params + listOf(cursor.recordedAt, cursor.id)
            val rows =
                connection.queryList(
                    "$SELECT_SQL WHERE $conditions ORDER BY recorded_at DESC, id DESC LIMIT ?",
                    params + fetch,
                    ObservationRows::toRecord,
                )
            val items = rows.take(page.limit)
            // A previous (newer) page exists only when this page came from a cursor and is not empty.
            val hasPrevious = cursor != null && items.isNotEmpty()
            return Page(
                items = items,
                nextCursor = if (rows.size > page.limit) PageCursor.after(items.last()).encode() else null,
                prevCursor = if (hasPrevious) PageCursor.before(items.first()).encode() else null,
                totalCount = total,
            )
        }

        // Step 4: going back: read the newer rows oldest first, then reverse them to newest first.
        val rows =
            connection.queryList(
                "$SELECT_SQL WHERE ${where.sql} AND (recorded_at, id) > (?, ?) " +
                    "ORDER BY recorded_at ASC, id ASC LIMIT ?",
                where.params + listOf(cursor.recordedAt, cursor.id, fetch),
                ObservationRows::toRecord,
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

    /** True when the cursor's row exists inside this query's scope. */
    private fun cursorExists(connection: Connection, where: Where, cursor: PageCursor): Boolean = connection.queryOne(
        "SELECT 1 FROM observations WHERE ${where.sql} AND id = ? AND recorded_at = ?",
        where.params + listOf(cursor.id, cursor.recordedAt),
    ) { true } == true

    /** True when the observation exists in this tenant and ward, whatever its version. */
    private fun observationExists(
        connection: Connection,
        tenantId: String,
        wardId: String,
        observationId: UUID,
    ): Boolean = connection.queryOne(
        "SELECT 1 FROM observations WHERE tenant_id = ? AND ward_id = ? AND id = ?",
        listOf(tenantId, wardId, observationId),
    ) { true } != null

    companion object {
        private const val SELECT_SQL = "SELECT ${ObservationRows.COLUMNS} FROM observations"
        // One `?` per column of `ObservationRows.COLUMNS` (17), bound in the same order by its insertParams.
        private const val INSERT_SQL =
            "INSERT INTO observations (${ObservationRows.COLUMNS}) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    }
}
