package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.FieldEncryptor
import com.carevoice.documentation.application.Resident
import com.carevoice.documentation.application.ResidentRepository
import javax.sql.DataSource

/** Repository pattern: residents; the display name is stored encrypted and decrypted here, at the boundary. */
class PostgresResidentRepository(private val dataSource: DataSource, private val encryptor: FieldEncryptor) :
    ResidentRepository {
    override suspend fun findById(tenantId: String, residentId: String): Resident? =
        withReadConnection(dataSource) { connection ->
            connection.queryOne(
                "SELECT id, tenant_id, ward_id, display_name_enc FROM residents WHERE tenant_id = ? AND id = ?",
                listOf(tenantId, residentId),
            ) { rs ->
                Resident(
                    id = rs.getString("id"),
                    tenantId = rs.getString("tenant_id"),
                    wardId = rs.getString("ward_id"),
                    displayName = encryptor.decrypt(rs.getString("display_name_enc")),
                )
            }
        }
}
