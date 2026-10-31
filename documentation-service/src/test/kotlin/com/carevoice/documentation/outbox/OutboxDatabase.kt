package com.carevoice.documentation.outbox

import com.carevoice.testsupport.TestDatabases
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * The real schema (every Flyway migration from the classpath, i.e. what production runs) in a schema of its own,
 * so the relay tests never collide with other test classes sharing the same PostgreSQL instance.
 */
object OutboxDatabase {
    private const val SCHEMA = "outbox_relay_test"

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
                // Enough for two relays (claimer, stats, workers) plus the test's own queries.
                maximumPoolSize = 30
                poolName = "outbox-relay-test"
            },
        )
    }

    fun truncate() {
        dataSource.connection.use { it.createStatement().use { statement -> statement.execute("TRUNCATE outbox") } }
    }

    /** Inserts [event] through the production writer, in its own committed transaction. */
    fun insert(event: OutboxEvent, now: Instant) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            OutboxWriter.insert(
                connection = connection,
                id = event.id,
                aggregateId = event.aggregateId,
                eventType = event.eventType,
                payloadJson = event.payload,
                now = now,
                requestId = event.requestId,
                traceparent = event.traceparent,
            )
            connection.commit()
        }
    }

    fun setAttempts(id: UUID, attempts: Int) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE outbox SET attempts = ? WHERE id = ?").use {
                it.setInt(1, attempts)
                it.setObject(2, id)
                it.executeUpdate()
            }
        }
    }

    fun row(id: UUID): OutboxRow = dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT status, attempts, next_attempt_at, last_error, published_at, request_id, traceparent FROM outbox WHERE id = ?",
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "outbox row $id not found" }
                    OutboxRow(
                        status = rows.getString("status"),
                        attempts = rows.getInt("attempts"),
                        nextAttemptAt = rows.getObject("next_attempt_at", OffsetDateTime::class.java).toInstant(),
                        lastError = rows.getString("last_error"),
                        publishedAt = rows.getObject("published_at", OffsetDateTime::class.java)?.toInstant(),
                        requestId = rows.getString("request_id"),
                        traceparent = rows.getString("traceparent"),
                    )
                }
            }
    }

    fun countByStatus(status: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM outbox WHERE status = ?").use { statement ->
            statement.setString(1, status)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }
}

data class OutboxRow(
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val lastError: String?,
    val publishedAt: Instant?,
    val requestId: String?,
    val traceparent: String?,
)
