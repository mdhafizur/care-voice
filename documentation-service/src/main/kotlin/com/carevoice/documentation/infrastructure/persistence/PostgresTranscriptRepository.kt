package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.FieldEncryptor
import com.carevoice.documentation.application.TranscriptInsert
import com.carevoice.documentation.application.TranscriptRecord
import com.carevoice.documentation.application.TranscriptRepository
import com.carevoice.documentation.infrastructure.serialization.StorageJson
import com.carevoice.documentation.infrastructure.serialization.StoredTranscriptResult
import com.carevoice.documentation.infrastructure.serialization.toDomain
import com.carevoice.documentation.infrastructure.serialization.toStored
import org.postgresql.util.PSQLException
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Repository pattern: transcripts in PostgreSQL. The raw text is encrypted ([FieldEncryptor]) before it reaches the
 * database and is never read back by the API; the stored `response_body` is what idempotent replays return.
 *
 * Idempotency is decided by the unique constraint `transcripts_idempotency_uq`, never by "check, then insert": the
 * database is the only place that sees both racing requests atomically.
 */
class PostgresTranscriptRepository(private val dataSource: DataSource, private val encryptor: FieldEncryptor) :
    TranscriptRepository {
    /**
     * Inserts the transcript with its text encrypted and the response stored for replays.
     * A second insert with the same idempotency key gives DuplicateIdempotencyKey instead of an error.
     */
    override suspend fun insert(record: TranscriptRecord, text: String): TranscriptInsert {
        val encrypted = encryptor.encrypt(text)
        val body = StorageJson.encodeToString(StoredTranscriptResult.serializer(), record.result.toStored())
        return withConnection(dataSource) { connection ->
            try {
                connection.update(
                    """
                    INSERT INTO transcripts (id, tenant_id, resident_id, created_by, idempotency_key, request_hash,
                                             text_enc, response_body, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                    """,
                    listOf(
                        record.id,
                        record.tenantId,
                        record.residentId,
                        record.createdBy,
                        record.idempotencyKey,
                        record.requestHash,
                        encrypted,
                        body,
                        record.createdAt,
                    ),
                )
                TranscriptInsert.Inserted
            } catch (e: SQLException) {
                if (isIdempotencyViolation(e)) TranscriptInsert.DuplicateIdempotencyKey else throw e
            }
        }
    }

    /** Finds the user's earlier transcript with this idempotency key, so a repeated request can be replayed. */
    override suspend fun findByIdempotencyKey(createdBy: String, idempotencyKey: UUID): TranscriptRecord? =
        withReadConnection(dataSource) { connection ->
            connection.queryOne(
                "$SELECT_SQL WHERE t.created_by = ? AND t.idempotency_key = ?",
                listOf(createdBy, idempotencyKey),
                ::toRecord,
            )
        }

    /** Finds a transcript only when its resident belongs to the given ward. */
    override suspend fun findById(tenantId: String, wardId: String, transcriptId: UUID): TranscriptRecord? =
        withReadConnection(dataSource) { connection ->
            connection.queryOne(
                "$SELECT_SQL WHERE t.id = ? AND t.tenant_id = ? AND r.ward_id = ?",
                listOf(transcriptId, tenantId, wardId),
                ::toRecord,
            )
        }

    /**
     * Deletes the encrypted text of up to [batchSize] transcripts created before [cutoff], oldest first, and returns
     * how many it erased. `SKIP LOCKED` lets two pods do this at once without waiting for each other.
     */
    override suspend fun eraseTextCreatedBefore(cutoff: Instant, batchSize: Int): Int =
        withConnection(dataSource) { connection ->
            connection.update(
                """
                UPDATE transcripts SET text_enc = NULL
                 WHERE id IN (SELECT id FROM transcripts
                               WHERE text_enc IS NOT NULL AND created_at < ?
                               ORDER BY created_at
                               LIMIT ?
                               FOR UPDATE SKIP LOCKED)
                """,
                listOf(cutoff, batchSize),
            )
        }

    /** Maps a row to a record; the stored response body is decoded back to the domain result. */
    private fun toRecord(rs: ResultSet): TranscriptRecord = TranscriptRecord(
        id = rs.uuid("id"),
        tenantId = rs.getString("tenant_id"),
        wardId = rs.getString("ward_id"),
        residentId = rs.getString("resident_id"),
        createdBy = rs.getString("created_by"),
        idempotencyKey = rs.uuid("idempotency_key"),
        requestHash = rs.getString("request_hash"),
        result = StorageJson.decodeFromString(
            StoredTranscriptResult.serializer(),
            rs.getString("response_body"),
        ).toDomain(),
        createdAt = rs.instant("created_at"),
    )

    private companion object {
        const val IDEMPOTENCY_CONSTRAINT = "transcripts_idempotency_uq"
        const val SELECT_SQL =
            """
            SELECT t.id, t.tenant_id, r.ward_id, t.resident_id, t.created_by, t.idempotency_key, t.request_hash,
                   t.response_body::text AS response_body, t.created_at
              FROM transcripts t
              JOIN residents r ON r.id = t.resident_id
            """

        /** SQLState 23505 on the idempotency constraint (any other unique violation is a bug and propagates). */
        fun isIdempotencyViolation(e: SQLException): Boolean {
            if (!SqlStates.isUniqueViolation(e)) return false
            val constraint = (e as? PSQLException)?.serverErrorMessage?.constraint
            return constraint == null || constraint == IDEMPOTENCY_CONSTRAINT
        }
    }
}
