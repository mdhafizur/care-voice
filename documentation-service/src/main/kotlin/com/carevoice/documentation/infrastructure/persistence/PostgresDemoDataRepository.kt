package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.DemoDataRepository
import com.carevoice.documentation.application.DemoDataset
import com.carevoice.documentation.application.FieldEncryptor
import javax.sql.DataSource

/**
 * Repository pattern: idempotent demo data. Every insert is `ON CONFLICT DO NOTHING`, so two pods seeding at the same
 * time, or a restart, never fail and never overwrite (e.g. a changed password hash stays as it was).
 */
class PostgresDemoDataRepository(private val dataSource: DataSource, private val encryptor: FieldEncryptor) :
    DemoDataRepository {
    /**
     * Inserts the tenant, wards, residents (names encrypted) and users, skipping rows that already exist.
     * Returns how many rows were really inserted.
     */
    override suspend fun insertIfAbsent(dataset: DemoDataset): Int = withConnection(dataSource) { connection ->
        var inserted =
            connection.update(
                "INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT DO NOTHING",
                listOf(dataset.tenantId, dataset.tenantName),
            )
        dataset.wards.forEach {
            inserted +=
                connection.update(
                    "INSERT INTO wards (id, tenant_id, name) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    listOf(it.id, dataset.tenantId, it.name),
                )
        }
        dataset.residents.forEach {
            inserted +=
                connection.update(
                    "INSERT INTO residents (id, tenant_id, ward_id, display_name_enc) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    listOf(it.id, it.tenantId, it.wardId, encryptor.encrypt(it.displayName)),
                )
        }
        dataset.users.forEach {
            inserted +=
                connection.update(
                    """
                        INSERT INTO users (username, tenant_id, ward_id, role, password_hash)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                        """,
                    listOf(it.username, it.tenantId, it.wardId, it.role.name, it.passwordHash),
                )
        }
        inserted
    }
}
