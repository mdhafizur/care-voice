package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.config.DatabaseConfig
import com.carevoice.documentation.config.ReadDatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import javax.sql.DataSource

/**
 * Connection pools: the primary (read-write) pool and an optional read-only pool on a streaming replica.
 *
 * Factory pattern: builds fully configured Hikari pools from typed config. Pools are small on purpose
 * (docs/CONTRACTS.md, section 14): with several pods (and PgBouncer in Kubernetes) the database's connection limit is
 * shared, and a handful of connections per pod saturate PostgreSQL long before more would help.
 */
class Database private constructor(
    val primary: HikariDataSource,
    private val replica: HikariDataSource?,
    meterRegistry: MeterRegistry? = null,
) : AutoCloseable {
    /**
     * The pool QueryObservations reads from. Falls back to the primary when no replica is configured; a replica that
     * is down is handled per query by [FallbackDataSource].
     */
    val read: DataSource = replica?.let { FallbackDataSource(it, primary, meterRegistry) } ?: primary

    val hasReplica: Boolean get() = replica != null

    /** Closes the replica pool (when there is one) and then the primary pool. */
    override fun close() {
        replica?.close()
        primary.close()
    }

    companion object {
        /**
         * Builds the primary pool and, when [readConfig] is set, a read-only replica pool.
         * The replica pool may start while the replica is unreachable; reads then use the primary.
         */
        fun create(config: DatabaseConfig, readConfig: ReadDatabaseConfig?, meterRegistry: MeterRegistry?): Database {
            // Connection pool (a set of reusable connections) for the primary database, which takes all writes.
            val primary =
                HikariDataSource(
                    hikariConfig(
                        "carevoice-primary",
                        config.url,
                        config.user,
                        config.password,
                        config.poolSize,
                        config.prepareThreshold,
                        false,
                    )
                        .apply { meterRegistry?.let { metricRegistry = it } },
                )
            // Optional read-only pool for the read replica (a read-only copy of the database).
            val replica =
                readConfig?.let {
                    HikariDataSource(
                        hikariConfig(
                            "carevoice-replica",
                            it.url,
                            it.user,
                            it.password,
                            config.poolSize,
                            config.prepareThreshold,
                            true,
                        )
                            .apply {
                                // Starting without a reachable replica is allowed: reads fall back to the primary.
                                // -1: Hikari skips the connection attempt at startup and connects in the background.
                                initializationFailTimeout = -1
                                meterRegistry?.let { registry -> metricRegistry = registry }
                            },
                    )
                }
            return Database(primary, replica, meterRegistry)
        }

        /** Wraps an existing data source (tests, tools). */
        fun of(primary: HikariDataSource, replica: HikariDataSource? = null): Database = Database(primary, replica)

        /** PgJDBC socketTimeout in seconds; well above statement_timeout (5 s), so it only fires for a dead peer. */
        private const val SOCKET_TIMEOUT_SECONDS = 30

        /**
         * The Hikari settings both pools share: a small pool, READ COMMITTED, short connect timeouts,
         * and a socket timeout so a dead database connection fails instead of hanging.
         */
        internal fun hikariConfig(
            poolName: String,
            url: String,
            user: String,
            password: String,
            poolSize: Int,
            prepareThreshold: Int?,
            readOnly: Boolean,
        ): HikariConfig = HikariConfig().apply {
            this.poolName = poolName
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = poolSize
            // Keep 2 idle connections ready (fewer when the pool is smaller).
            minimumIdle = minOf(2, poolSize)
            isAutoCommit = true
            isReadOnly = readOnly
            // READ COMMITTED (PostgreSQL's default, stated explicitly): every statement sees committed data; the
            // correctness rules rely on unique constraints, version checks and SKIP LOCKED, not on isolation.
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            // Times in milliseconds. connectionTimeout: wait at most 3 s for a free connection from the pool, then
            // fail (SQLTransientConnectionException, which becomes a 503). validationTimeout: at most 1 s to check
            // that a connection is alive. maxLifetime: replace every connection after 30 minutes.
            connectionTimeout = 3_000
            validationTimeout = 1_000
            maxLifetime = 30 * 60_000L
            // PgJDBC: 0 disables server-side prepared statements (PgBouncer transaction mode < 1.21).
            prepareThreshold?.let { addDataSourceProperty("prepareThreshold", it) }
            addDataSourceProperty("ApplicationName", "documentation-service")
            addDataSourceProperty("tcpKeepAlive", true)
            // Last line of defence below the server's statement_timeout (5 s, migration V4): a connection whose server
            // stops answering (network partition, frozen node) fails after 30 s instead of hanging a request forever.
            addDataSourceProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS)
        }
    }
}
