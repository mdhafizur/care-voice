package com.carevoice.documentation.support

import com.carevoice.testsupport.TestDatabases
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * The production schema (Flyway migrations from the classpath) in a schema of its own on the shared test PostgreSQL,
 * so API tests never collide with the outbox relay tests (which truncate their own schema). Isolation between API
 * tests comes from per-test tenants, wards, residents and users (see [TestScenario]), not from cleanup.
 */
object TestDatabase {
    private const val SCHEMA = "documentation_api_test"

    val dataSource: DataSource by lazy {
        val postgres = TestDatabases.postgres
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                schema = SCHEMA
                maximumPoolSize = 20
                poolName = "documentation-api-test"
            },
        )
    }

    /** Runs [sql] with positional [params] and returns every row as a column-name → value map. */
    fun query(sql: String, vararg params: Any?): List<Map<String, Any?>> = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rs ->
                val meta = rs.metaData
                buildList {
                    while (rs.next()) {
                        add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getObject(it) })
                    }
                }
            }
        }
    }

    fun execute(sql: String, vararg params: Any?): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    fun count(sql: String, vararg params: Any?): Long =
        (query(sql, *params).single().values.single() as Number).toLong()
}
