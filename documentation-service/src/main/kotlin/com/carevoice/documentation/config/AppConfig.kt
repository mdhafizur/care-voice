package com.carevoice.documentation.config

import java.net.URI
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
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

        /** Loads only the database settings (for `migrate`), so the migration Job needs no JWT or encryption secrets. */
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

/**
 * Collects errors instead of throwing on the first one. Every accessor returns a usable placeholder after recording
 * an error, so loading continues and the final [ConfigException] lists everything that is wrong.
 */
private class ConfigLoader(private val env: Map<String, String>) {
    private val errors = mutableListOf<String>()

    /** Only the primary database settings: all the `migrate` one-shot (Kubernetes Job, compose) needs. */
    fun loadDatabase(): DatabaseConfig {
        val database = database()
        if (errors.isNotEmpty()) throw ConfigException(errors.toList())
        return database
    }

    /** Reads the primary database settings. `DATABASE_MIGRATION_URL` falls back to `DATABASE_URL`. */
    private fun database(): DatabaseConfig {
        val databaseUrl = jdbcUrl("DATABASE_URL", required = true) ?: ""
        return DatabaseConfig(
            url = databaseUrl,
            user = required("DATABASE_USER"),
            password = required("DATABASE_PASSWORD"),
            poolSize = int("DATABASE_POOL_SIZE", default = 10, range = 1..200),
            migrationUrl = jdbcUrl("DATABASE_MIGRATION_URL", required = false) ?: databaseUrl,
            prepareThreshold = optionalInt("DATABASE_PREPARE_THRESHOLD", range = 0..1_000_000),
            migrateOnStartup = boolean("DATABASE_MIGRATE_ON_STARTUP", default = true),
        )
    }

    /**
     * Reads and checks every setting, then throws one [ConfigException] when anything was wrong.
     * The read replica, when `DATABASE_READ_URL` is set, reuses the primary's user and password unless its own are set.
     */
    fun load(): AppConfig {
        val database = database()
        val readDatabase =
            jdbcUrl("DATABASE_READ_URL", required = false)?.let { url ->
                ReadDatabaseConfig(
                    url = url,
                    user = optional("DATABASE_READ_USER") ?: database.user,
                    password = optional("DATABASE_READ_PASSWORD") ?: database.password,
                )
            }
        val transport = eventTransport()
        val alertServiceUrl = httpUrl("ALERT_SERVICE_URL", required = transport == EventTransport.HTTP)
        val rabbitmqUrl = uri("RABBITMQ_URL", setOf("amqp", "amqps"), required = transport == EventTransport.AMQP)
        val aiEnabled = boolean("AI_FALLBACK_ENABLED", default = false)
        val aiProvider = aiProvider()
        val apiKeyName = if (aiProvider == AiProvider.GEMINI) "GEMINI_API_KEY" else "ANTHROPIC_API_KEY"
        val apiKey = if (aiEnabled) required(apiKeyName) else optional(apiKeyName)
        val demoEnabled = boolean("DEMO_SEED_ENABLED", default = false)
        val demoPassword = if (demoEnabled) demoPassword() else optional("DEMO_PASSWORD")

        val config =
            AppConfig(
                httpPort = int("HTTP_PORT", default = 8080, range = 1..65_535),
                database = database,
                readDatabase = readDatabase,
                jwt =
                JwtConfig(
                    secret = jwtSecret(),
                    issuer = optional("JWT_ISSUER") ?: "carevoice-auth",
                    audience = optional("JWT_AUDIENCE") ?: "carevoice",
                    accessTokenTtl = int("JWT_TTL_SECONDS", default = 900, range = 60..86_400).seconds,
                ),
                encryption = encryption(),
                eventTransport = transport,
                alertServiceUrl = alertServiceUrl,
                rabbitmqUrl = rabbitmqUrl,
                redisUrl = uri("REDIS_URL", setOf("redis", "rediss", "redis-sentinel"), required = true) ?: "",
                ai = AiConfig(
                    enabled = aiEnabled,
                    apiKey = apiKey?.takeIf { it.isNotBlank() },
                    model =
                    when (aiProvider) {
                        AiProvider.CLAUDE -> optional("AI_MODEL") ?: "claude-haiku-5-5"
                        AiProvider.GEMINI -> optional("GEMINI_MODEL") ?: "gemini-flash-latest"
                    },
                    provider = aiProvider,
                    thinkingLevel = if (aiProvider == AiProvider.GEMINI) geminiThinkingLevel() else null,
                    timeout = optionalInt("AI_TIMEOUT_MS", range = 500..30_000)?.milliseconds,
                ),
                transcriptRetention = int("TRANSCRIPT_RETENTION_DAYS", default = 30, range = 1..3_650).days,
                demoSeed = DemoSeedConfig(demoEnabled, demoPassword),
                rateLimitPerMinute = int("RATE_LIMIT_PER_MINUTE", default = 120, range = 1..100_000),
                requestTimeout = int("REQUEST_TIMEOUT_MS", default = 30_000, range = 1_000..120_000).milliseconds,
                trustedProxies = networks("TRUSTED_PROXIES"),
            )
        if (errors.isNotEmpty()) throw ConfigException(errors.toList())
        return config
    }

    /** The trimmed value of [name], or null when it is unset or blank. */
    private fun optional(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

    /** Comma-separated CIDR networks (`10.42.0.0/16,fd00::/8`); empty when unset. Every invalid entry is reported. */
    private fun networks(name: String): List<IpNetwork> {
        val entries = optional(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return entries.mapNotNull { entry ->
            IpNetwork.parse(entry) ?: null.also { errors += "$name: '$entry' is not an IP address or CIDR network" }
        }
    }

    /** Like [optional], but records an error and returns an empty string when the value is missing. */
    private fun required(name: String): String = optional(name) ?: run {
        errors += "$name is required"
        ""
    }

    /** The integer value of [name], or [default] when it is unset or invalid (an invalid value is also recorded). */
    private fun int(name: String, default: Int, range: IntRange): Int = optionalInt(name, range) ?: default

    /** The integer value of [name], or null. A value that is not a number in [range] is recorded as an error. */
    private fun optionalInt(name: String, range: IntRange): Int? {
        val raw = optional(name) ?: return null
        val value = raw.toIntOrNull()
        if (value == null || value !in range) {
            errors += "$name must be an integer in ${range.first}..${range.last}"
            return null
        }
        return value
    }

    /** Accepts `true` or `false` in any letter case; anything else is recorded and gives [default]. */
    private fun boolean(name: String, default: Boolean): Boolean = when (optional(name)?.lowercase()) {
        null -> default

        "true" -> true

        "false" -> false

        else -> {
            errors += "$name must be 'true' or 'false'"
            default
        }
    }

    /** A value that must start with `jdbc:postgresql://`; otherwise an error is recorded and null returned. */
    private fun jdbcUrl(name: String, required: Boolean): String? {
        val value = if (required) required(name).takeIf { it.isNotEmpty() } else optional(name)
        if (value != null && !value.startsWith("jdbc:postgresql://")) {
            errors += "$name must be a PostgreSQL JDBC URL (jdbc:postgresql://host:port/database)"
            return null
        }
        return value
    }

    /** A URL that must use `http` or `https`. */
    private fun httpUrl(name: String, required: Boolean): String? = uri(name, setOf("http", "https"), required)

    /** A URL whose scheme must be one of [schemes]. The scheme is read from the text before the first comma. */
    private fun uri(name: String, schemes: Set<String>, required: Boolean): String? {
        val value = if (required) required(name).takeIf { it.isNotEmpty() } else optional(name)
        value ?: return null
        val scheme = runCatching { URI(value.substringBefore(',')).scheme }.getOrNull()?.lowercase()
        if (scheme !in schemes) {
            errors += "$name must be a URL with scheme ${schemes.joinToString(" or ")}"
            return null
        }
        return value
    }

    /** `AI_PROVIDER`: `claude` (the default) or `gemini`, in any letter case. */
    private fun aiProvider(): AiProvider = when (optional("AI_PROVIDER")?.lowercase()) {
        null, "claude" -> AiProvider.CLAUDE

        "gemini" -> AiProvider.GEMINI

        else -> {
            errors += "AI_PROVIDER must be 'claude' or 'gemini'"
            AiProvider.CLAUDE
        }
    }

    /** `low` by default (the lowest level Gemini 3.8 Flash accepts); `none` omits thinkingConfig. */
    private fun geminiThinkingLevel(): String? = when (val level = optional("GEMINI_THINKING_LEVEL")?.lowercase()) {
        null -> "low"

        "none" -> null

        in GEMINI_THINKING_LEVELS -> level

        else -> {
            errors += "GEMINI_THINKING_LEVEL must be one of ${GEMINI_THINKING_LEVELS.joinToString()} or none"
            "low"
        }
    }

    /** `EVENT_TRANSPORT`: `amqp` (the default) or `http`, in any letter case. */
    private fun eventTransport(): EventTransport = when (optional("EVENT_TRANSPORT")?.lowercase()) {
        null, "amqp" -> EventTransport.AMQP

        "http" -> EventTransport.HTTP

        else -> {
            errors += "EVENT_TRANSPORT must be 'amqp' or 'http'"
            EventTransport.AMQP
        }
    }

    /** `JWT_SECRET` as UTF-8 bytes; it must be at least [MIN_JWT_SECRET_BYTES] long. */
    private fun jwtSecret(): ByteArray {
        val secret = required("JWT_SECRET")
        val bytes = secret.toByteArray(Charsets.UTF_8)
        if (secret.isNotEmpty() && bytes.size < MIN_JWT_SECRET_BYTES) {
            errors += "JWT_SECRET must be at least $MIN_JWT_SECRET_BYTES bytes (HS256 needs a key as long as the hash)"
        }
        return bytes
    }

    /**
     * `DATA_ENCRYPTION_KEY` / `DATA_ENCRYPTION_KEY_ID` is the active key. Retired keys that must stay readable during
     * a rotation go in `DATA_ENCRYPTION_PREVIOUS_KEYS` as `id:base64,id:base64`.
     */
    private fun encryption(): EncryptionConfig {
        val activeId = optional("DATA_ENCRYPTION_KEY_ID") ?: "v1"
        if (!KEY_ID.matches(activeId)) errors += "DATA_ENCRYPTION_KEY_ID must match ${KEY_ID.pattern}"
        val keys = linkedMapOf<String, ByteArray>()
        decodeKey("DATA_ENCRYPTION_KEY", required("DATA_ENCRYPTION_KEY"))?.let { keys[activeId] = it }
        optional("DATA_ENCRYPTION_PREVIOUS_KEYS")?.split(',')?.map {
            it.trim()
        }?.filter { it.isNotEmpty() }?.forEach { entry ->
            val id = entry.substringBefore(':', missingDelimiterValue = "")
            val key = entry.substringAfter(':', missingDelimiterValue = "")
            when {
                !KEY_ID.matches(id) || key.isEmpty() ->
                    errors += "DATA_ENCRYPTION_PREVIOUS_KEYS entries must look like '<keyId>:<base64 key>'"

                id in keys -> errors += "DATA_ENCRYPTION_PREVIOUS_KEYS repeats key id '$id'"

                else -> decodeKey("DATA_ENCRYPTION_PREVIOUS_KEYS ($id)", key)?.let { keys[id] = it }
            }
        }
        return EncryptionConfig(activeId, keys)
    }

    /** Decodes a base64 AES-256 key (exactly 32 bytes). Returns null for an empty value or a bad key (recorded). */
    private fun decodeKey(name: String, base64: String): ByteArray? {
        if (base64.isEmpty()) return null
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
        if (bytes == null || bytes.size != AES_256_KEY_BYTES) {
            errors += "$name must be base64 of exactly $AES_256_KEY_BYTES bytes"
            return null
        }
        return bytes
    }

    /** `DEMO_PASSWORD`, required when demo seeding is on, with at least [MIN_DEMO_PASSWORD_LENGTH] characters. */
    private fun demoPassword(): String {
        val password = required("DEMO_PASSWORD")
        if (password.isNotEmpty() && password.length < MIN_DEMO_PASSWORD_LENGTH) {
            errors += "DEMO_PASSWORD must be at least $MIN_DEMO_PASSWORD_LENGTH characters"
        }
        return password
    }

    companion object {
        const val MIN_JWT_SECRET_BYTES = 32
        const val AES_256_KEY_BYTES = 32
        const val MIN_DEMO_PASSWORD_LENGTH = 8
        val GEMINI_THINKING_LEVELS = listOf("minimal", "low", "medium", "high")
        val KEY_ID = Regex("[A-Za-z0-9_-]{1,16}")
    }
}

/** Replaces the user-info part of a URL (`user:secret@`) with three asterisks, for logging. */
internal fun redactUserInfo(url: String): String = url.replace(Regex("(?<=://)[^@/]+@"), "***@")
