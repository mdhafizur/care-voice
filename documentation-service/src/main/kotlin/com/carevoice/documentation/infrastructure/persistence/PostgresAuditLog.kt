package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.AuditEntry
import com.carevoice.documentation.application.AuditLog
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.sql.DataSource

/** Adapter: audit rows in PostgreSQL, written inside the caller's transaction so they commit with the business change. */
class PostgresAuditLog(private val dataSource: DataSource) : AuditLog {
    /** Inserts one audit row, with the details stored as a JSON object (jsonb). */
    override suspend fun record(entry: AuditEntry) {
        val details = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), entry.details)
        withConnection(dataSource) { connection ->
            connection.update(
                """
                INSERT INTO audit_log (tenant_id, actor, action, entity_type, entity_id, occurred_at, details)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """,
                listOf(
                    entry.tenantId,
                    entry.actor,
                    entry.action,
                    entry.entityType,
                    entry.entityId,
                    entry.occurredAt,
                    details,
                ),
            )
        }
    }
}
