package com.carevoice.documentation.infrastructure.health

import com.carevoice.documentation.application.HealthProbe
import com.carevoice.documentation.application.HealthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Adapter: readiness of PostgreSQL (critical: without the primary nothing can be documented). */
class PostgresHealthProbe(
    private val dataSource: DataSource,
    override val name: String = "database",
    override val critical: Boolean = true,
    private val timeout: Duration = 2.seconds,
) : HealthProbe {
    /**
     * A failure to connect is thrown, not swallowed: [com.carevoice.documentation.application.Readiness] turns it
     * into DOWN and logs the cause once. A timeout (null) is DOWN without an exception.
     */
    override suspend fun check(): HealthStatus = withTimeoutOrNull(timeout) {
        withContext(Dispatchers.IO) {
            // JDBC isValid takes whole seconds, and 0 means "no time limit", so at least 1 s is passed.
            dataSource.connection.use { it.isValid(timeout.inWholeSeconds.toInt().coerceAtLeast(1)) }
        }
    }.let {
        if (it == true) {
            HealthStatus.UP
        } else if (critical) {
            HealthStatus.DOWN
        } else {
            HealthStatus.DEGRADED
        }
    }
}

/** Adapter: any non-critical dependency exposing a boolean (Redis connection, RabbitMQ connection). */
class FlagHealthProbe(override val name: String, private val isUp: () -> Boolean) : HealthProbe {
    override val critical: Boolean = false

    /** An exception from [isUp] reaches the readiness use case, which reports DEGRADED and logs the cause once. */
    override suspend fun check(): HealthStatus = if (isUp()) HealthStatus.UP else HealthStatus.DEGRADED
}
