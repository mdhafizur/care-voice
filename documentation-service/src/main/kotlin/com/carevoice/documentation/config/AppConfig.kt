package com.carevoice.documentation.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The complete, typed configuration of documentation-service (docs/CONTRACTS.md, section 4).
 *
 * Why one immutable object loaded once at startup instead of reading `System.getenv` where a value is needed:
 * every value is parsed and validated in one place, BEFORE anything opens a connection, and a misconfigured pod fails
 * immediately with a list of every problem ("fail fast, all errors at once") instead of crashing on the first request
 * that happens to touch the missing value, possibly hours later. Everything downstream receives plain typed values
 * through constructors, which is also what makes the service testable without environment variables.
 */
data class AppConfig(
    val httpPort: Int,
    val database: DatabaseConfig,
    val readDatabase: ReadDatabaseConfig?,
    val jwt: JwtConfig,
    val encryption: EncryptionConfig,
    val eventTransport: EventTransport,
    val alertServiceUrl: String?,
    val rabbitmqUrl: String?,
    val redisUrl: String,
    val ai: AiConfig,
    val transcriptRetention: Duration,
    val demoSeed: DemoSeedConfig,
    val rateLimitPerMinute: Int,
    val requestTimeout: Duration = 30.seconds,
    val trustedProxies: List<IpNetwork> = emptyList(),
) {
    /** Never prints secrets, so the config can be logged at startup and appear in failed assertions safely. */
    override fun toString(): String =
        "AppConfig(httpPort=$httpPort, database=$database, readDatabase=$readDatabase, jwt=$jwt, " +
            "encryption=$encryption, eventTransport=$eventTransport, alertServiceUrl=$alertServiceUrl, " +
            "rabbitmqUrl=${rabbitmqUrl?.let { redactUserInfo(it) }}, redisUrl=${redactUserInfo(redisUrl)}, ai=$ai, " +
            "transcriptRetention=$transcriptRetention, demoSeed=$demoSeed, rateLimitPerMinute=$rateLimitPerMinute, " +
            "requestTimeout=$requestTimeout, trustedProxies=$trustedProxies)"

    companion object {
        /**
         * Loads and validates the configuration from [env] (normally `System.getenv()`).
         *
         * @throws ConfigException listing EVERY missing or invalid value, not just the first one.
         */
        fun load(env: Map<String, String> = System.getenv()): AppConfig = ConfigLoader(env).load()

        /** Loads only the database settings (for `migrate`): the migration Job needs no JWT or encryption secrets. */
        fun loadDatabase(env: Map<String, String> = System.getenv()): DatabaseConfig = ConfigLoader(env).loadDatabase()
    }
}

/**
 * The primary (read-write) database.
 *
 * @property migrationUrl direct Postgres connection used only by Flyway. Behind PgBouncer in transaction mode the app
 *   pool cannot be used for migrations: Flyway holds a session-level advisory lock for the whole run, and a
 *   transaction pooler hands the session to other clients between transactions (docs/CONTRACTS.md, section 14).
 * @property prepareThreshold passed to PgJDBC as `prepareThreshold`; `0` disables server-side prepared statements,
 *   which PgBouncer < 1.21 in transaction mode cannot track. Null keeps the driver default (5).
 */
data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val poolSize: Int,
    val migrationUrl: String,
    val prepareThreshold: Int?,
    val migrateOnStartup: Boolean,
) {
    /** Shows every field except the password. */
    override fun toString(): String =
        "DatabaseConfig(url=$url, user=$user, password=***, poolSize=$poolSize, migrationUrl=$migrationUrl, " +
            "prepareThreshold=$prepareThreshold, migrateOnStartup=$migrateOnStartup)"
}

/** Optional streaming replica used ONLY by QueryObservations (docs/CONTRACTS.md, section 14, "High availability"). */
data class ReadDatabaseConfig(val url: String, val user: String, val password: String) {
    /** Shows the URL and user, never the password. */
    override fun toString(): String = "ReadDatabaseConfig(url=$url, user=$user, password=***)"
}

data class JwtConfig(val secret: ByteArray, val issuer: String, val audience: String, val accessTokenTtl: Duration) {
    /** Compares [secret] by its bytes. A data class would compare arrays by identity (the same object) only. */
    override fun equals(other: Any?): Boolean =
        other is JwtConfig && secret.contentEquals(other.secret) && issuer == other.issuer &&
            audience == other.audience && accessTokenTtl == other.accessTokenTtl

    /** Built from the secret's bytes, so it matches [equals]. */
    override fun hashCode(): Int =
        arrayOf<Any>(secret.contentHashCode(), issuer, audience, accessTokenTtl).contentHashCode()

    /** Hides the secret. */
    override fun toString(): String =
        "JwtConfig(secret=***, issuer=$issuer, audience=$audience, accessTokenTtl=$accessTokenTtl)"
}

/**
 * Field-encryption keys. [activeKeyId] encrypts new values; every key in [keys] (including retired ones) can still
 * decrypt, which is what makes key rotation possible without re-encrypting the whole database in one go.
 */
data class EncryptionConfig(val activeKeyId: String, val keys: Map<String, ByteArray>) {
    /** Compares the key bytes, not the array objects, so two configs with the same keys are equal. */
    override fun equals(other: Any?): Boolean =
        other is EncryptionConfig && activeKeyId == other.activeKeyId && keys.keys == other.keys.keys &&
            keys.all { (id, key) -> key.contentEquals(other.keys.getValue(id)) }

    /** Uses the key ids only, not the key bytes; this still agrees with [equals]. */
    override fun hashCode(): Int = activeKeyId.hashCode() * 31 + keys.keys.hashCode()

    /** Lists the key ids only, never the keys. */
    override fun toString(): String = "EncryptionConfig(activeKeyId=$activeKeyId, keyIds=${keys.keys})"
}

enum class EventTransport { AMQP, HTTP }

/** Which model API the AI fallback calls (`AI_PROVIDER`). */
enum class AiProvider { CLAUDE, GEMINI }

/**
 * @property apiKey the key of the selected [provider] (`ANTHROPIC_API_KEY` or `GEMINI_API_KEY`).
 * @property model `AI_MODEL` for Claude, `GEMINI_MODEL` for Gemini: separate variables, so switching the provider
 *   never sends one vendor's model name to the other.
 * @property thinkingLevel Gemini's `thinkingLevel` (`GEMINI_THINKING_LEVEL`, default `low`; `none` sends no thinking
 *   configuration, for Gemini 2.5 models); always null for Claude.
 * @property timeout per HTTP attempt (`AI_TIMEOUT_MS`, 500 to 30000); null means the provider's default (Claude 3 s,
 *   Gemini 5 s). The nurse waits for it, so raise it only as far as the model's real latency needs.
 */
data class AiConfig(
    val enabled: Boolean,
    val apiKey: String?,
    val model: String,
    val provider: AiProvider = AiProvider.CLAUDE,
    val thinkingLevel: String? = null,
    val timeout: Duration? = null,
) {
    /** Hides the API key; it only shows whether one is set. */
    override fun toString(): String =
        "AiConfig(enabled=$enabled, provider=$provider, apiKey=${apiKey?.let { "***" }}, model=$model, " +
            "thinkingLevel=$thinkingLevel, timeout=$timeout)"
}

data class DemoSeedConfig(val enabled: Boolean, val password: String?) {
    /** Hides the password; it only shows whether one is set. */
    override fun toString(): String = "DemoSeedConfig(enabled=$enabled, password=${password?.let { "***" }})"
}

/** Thrown by [AppConfig.load]; [errors] names every offending variable, never its (possibly secret) value. */
class ConfigException(val errors: List<String>) :
    RuntimeException("Invalid configuration:\n" + errors.joinToString("\n") { "  - $it" })

/** Replaces the user-info part of a URL (`user:secret@`) with three asterisks, for logging. */
internal fun redactUserInfo(url: String): String = url.replace(Regex("(?<=://)[^@/]+@"), "***@")
