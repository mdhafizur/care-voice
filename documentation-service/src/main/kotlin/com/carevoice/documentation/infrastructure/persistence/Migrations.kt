package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.config.DatabaseConfig
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Flyway, configured explicitly in code (docs/CONTRACTS.md, section 14) so nothing depends on a properties file or
 * on Flyway's defaults changing between versions.
 *
 * Connects through [DatabaseConfig.migrationUrl], a DIRECT PostgreSQL connection: Flyway holds a session-level
 * advisory lock for the whole run (that lock is what makes simultaneous pod starts safe), and a transaction-mode
 * pooler would hand that session to other clients between transactions.
 */
class Migrations(private val config: DatabaseConfig) {
    private val log = LoggerFactory.getLogger(Migrations::class.java)

    private val flyway: Flyway by lazy {
        Flyway
            .configure()
            .dataSource(config.migrationUrl, config.user, config.password)
            .locations("classpath:db/migration")
            .table("flyway_schema_history")
            .validateOnMigrate(true)
            .cleanDisabled(true)
            .outOfOrder(false)
            .baselineOnMigrate(false)
            .load()
    }

    /** Applies pending migrations; a no-op when the schema is current. */
    fun migrate(): MigrateResult {
        val result = flyway.migrate()
        log.event(
            Level.INFO,
            LogType.DB_OPERATION,
            "Database migrations applied",
            "migrationsExecuted" to result.migrationsExecuted,
            "targetSchemaVersion" to result.targetSchemaVersion,
        )
        return result
    }

    /**
     * Refuses to start on a pending, missing or modified migration (used when `DATABASE_MIGRATE_ON_STARTUP=false`:
     * a separate Job migrates, and the app must not run against a schema it was not built for).
     *
     * @throws IllegalStateException listing every problem
     */
    fun validate() {
        val result = flyway.validateWithResult()
        if (!result.validationSuccessful) {
            val problems = result.invalidMigrations.joinToString("; ") {
                "${it.version}: ${it.errorDetails?.errorMessage}"
            }
            throw IllegalStateException("Database schema does not match this build: $problems")
        }
    }

    /** Startup policy: migrate, or only validate. */
    fun onStartup() {
        if (config.migrateOnStartup) migrate() else validate()
    }
}
