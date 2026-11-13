package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

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
        // Step 1: try the preferred connection first.
        val connection =
            try {
                preferredConnection()
            } catch (e: SQLException) {
                // Step 2: if that fails, count and log it, then use the fallback connection instead.
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
        // Step 3: the preferred connection works again after an outage:
        // log once that it is back.
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
