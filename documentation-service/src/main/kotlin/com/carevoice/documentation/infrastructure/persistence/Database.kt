package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.config.DatabaseConfig
import com.carevoice.documentation.config.ReadDatabaseConfig
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
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
            minimumIdle = minOf(2, poolSize)
            isAutoCommit = true
            isReadOnly = readOnly
            // READ COMMITTED (PostgreSQL's default, stated explicitly): every statement sees committed data; the
            // correctness rules rely on unique constraints, version checks and SKIP LOCKED, not on isolation.
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
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

/**
 * Tries the replica first and falls back to the primary when it cannot hand out a connection (degraded, but
 * available). Decorator pattern over [DataSource].
 *
 * The fallback is invisible to callers, so it must not be invisible to operators: every fallback is counted in
 * `carevoice_db_read_fallback_total`, and the switch to the primary and back is logged once each (WARN with the cause,
 * then INFO), not on every query.
 */
internal class FallbackDataSource(
    private val preferred: DataSource,
    private val fallback: DataSource,
    meterRegistry: MeterRegistry? = null,
) : DataSource by preferred {
    private val fallingBack = AtomicBoolean(false)
    private val fallbacks: Counter? =
        meterRegistry?.let {
            Counter
                .builder(READ_FALLBACK_METRIC)
                .description("Reads served by the primary because the replica could not hand out a connection")
                .register(it)
        }

    /** Gets a connection from the replica, or from the primary when the replica fails. */
    override fun getConnection(): java.sql.Connection = withFallback({ preferred.connection }, { fallback.connection })

    /** Same as the no-argument version, with explicit credentials for both pools. */
    override fun getConnection(username: String?, password: String?): java.sql.Connection = withFallback(
        { preferred.getConnection(username, password) },
        { fallback.getConnection(username, password) },
    )

    /**
     * Tries [preferredConnection] and uses [fallbackConnection] when it throws an [SQLException].
     * Counts every fallback, and logs only the switch to the primary (WARN) and the switch back (INFO).
     */
    private fun withFallback(
        preferredConnection: () -> java.sql.Connection,
        fallbackConnection: () -> java.sql.Connection,
    ): java.sql.Connection {
        val connection =
            try {
                preferredConnection()
            } catch (e: SQLException) {
                fallbacks?.increment()
                if (fallingBack.compareAndSet(false, true)) {
                    log.event(
                        Level.WARN,
                        LogType.DB_OPERATION,
                        "Read replica unavailable; reads fall back to the primary",
                        "sqlState" to e.sqlState,
                        cause = e,
                    )
                } else {
                    // The WARN above fires once per outage; every further read served by the primary is DEBUG.
                    log.event(
                        Level.DEBUG,
                        LogType.DB_OPERATION,
                        "Read served by the primary: replica unavailable",
                        "sqlState" to e.sqlState,
                    )
                }
                return fallbackConnection()
            }
        if (fallingBack.compareAndSet(true, false)) {
            log.event(Level.INFO, LogType.DB_OPERATION, "Read replica available again; reads use the replica")
        }
        return connection
    }

    companion object {
        /** `carevoice_db_read_fallback_total` */
        const val READ_FALLBACK_METRIC = "carevoice.db.read.fallback"

        private val log = LoggerFactory.getLogger(FallbackDataSource::class.java)
    }
}
