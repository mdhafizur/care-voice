package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.Role
import com.carevoice.documentation.application.UserAccount
import com.carevoice.documentation.application.UserRepository
import javax.sql.DataSource

/** Repository pattern: login accounts. */
class PostgresUserRepository(private val dataSource: DataSource) : UserRepository {
    /** Loads the account by username; a row with an unknown role gives null, so that user cannot log in. */
    override suspend fun findByUsername(username: String): UserAccount? = withReadConnection(dataSource) { connection ->
        connection.queryOne(
            "SELECT username, tenant_id, ward_id, role, password_hash FROM users WHERE username = ?",
            listOf(username),
        ) { rs ->
            Role.parse(rs.getString("role"))?.let { role ->
                UserAccount(
                    username = rs.getString("username"),
                    tenantId = rs.getString("tenant_id"),
                    wardId = rs.getString("ward_id"),
                    role = role,
                    passwordHash = rs.getString("password_hash"),
                )
            }
        }
    }
}
