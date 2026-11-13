package com.carevoice.documentation.config

import java.net.URI
import java.util.Base64
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Collects errors instead of throwing on the first one. Every accessor returns a usable placeholder after recording
 * an error, so loading continues and the final [ConfigException] lists everything that is wrong.
 */
internal class ConfigLoader(private val env: Map<String, String>) {
    private val errors = mutableListOf<String>()

    /** Only the primary database settings: all the `migrate` one-shot (Kubernetes Job, compose) needs. */
    fun loadDatabase(): DatabaseConfig {
        val database = database()
        throwIfAnyErrors()
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
        // Primary database and the optional read replica (a read-only copy).
        val database = database()
        val readDatabase =
            jdbcUrl("DATABASE_READ_URL", required = false)?.let { url ->
                ReadDatabaseConfig(
                    url = url,
                    user = optional("DATABASE_READ_USER") ?: database.user,
                    password = optional("DATABASE_READ_PASSWORD") ?: database.password,
                )
            }
        // Event transport: only the URL of the chosen transport is required.
        val transport = eventTransport()
        val alertServiceUrl = httpUrl("ALERT_SERVICE_URL", required = transport == EventTransport.HTTP)
        val rabbitmqUrl = uri("RABBITMQ_URL", setOf("amqp", "amqps"), required = transport == EventTransport.AMQP)
        // AI fallback: its API key is required only when it is turned on.
        val aiEnabled = boolean("AI_FALLBACK_ENABLED", default = false)
        val aiProvider = aiProvider()
        val apiKeyName = if (aiProvider == AiProvider.GEMINI) "GEMINI_API_KEY" else "ANTHROPIC_API_KEY"
        val apiKey = if (aiEnabled) required(apiKeyName) else optional(apiKeyName)
        // Demo data: a password is required only when seeding is turned on.
        val demoEnabled = boolean("DEMO_SEED_ENABLED", default = false)
        val demoPassword = if (demoEnabled) demoPassword() else optional("DEMO_PASSWORD")

        // Build the config. Settings that need no check across values are read right here.
        // The defaults and allowed ranges below are the ones in docs/CONTRACTS.md, section 4; keep both in sync.
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
                    // 900 s = 15 minutes; allowed: 1 minute to 24 hours.
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
        throwIfAnyErrors()
        return config
    }

    /** Throws one [ConfigException] that lists every recorded error; does nothing when there is none. */
    private fun throwIfAnyErrors() {
        if (errors.isNotEmpty()) throw ConfigException(errors.toList())
    }

    /** The trimmed value of [name], or null when it is unset or blank. */
    private fun optional(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

    /** The comma-separated entries of [name], each trimmed, without empty ones; an empty list when unset. */
    private fun commaSeparated(name: String): List<String> =
        optional(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** Comma-separated CIDR networks (`10.42.0.0/16,fd00::/8`); empty when unset. Every invalid entry is reported. */
    private fun networks(name: String): List<IpNetwork> = commaSeparated(name).mapNotNull { entry ->
        val network = IpNetwork.parse(entry)
        if (network == null) errors += "$name: '$entry' is not an IP address or CIDR network"
        network
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

    /**
     * The value of [name], or null when it is unset or blank. When [required] is true, a missing value is also
     * recorded as an error.
     */
    private fun requiredOrOptional(name: String, required: Boolean): String? =
        if (required) required(name).takeIf { it.isNotEmpty() } else optional(name)

    /** A value that must start with `jdbc:postgresql://`; otherwise an error is recorded and null returned. */
    private fun jdbcUrl(name: String, required: Boolean): String? {
        val value = requiredOrOptional(name, required)
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
        val value = requiredOrOptional(name, required)
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
        // Step 1: the id of the active key, which must have a valid form.
        val activeId = optional("DATA_ENCRYPTION_KEY_ID") ?: "v1"
        if (!KEY_ID.matches(activeId)) errors += "DATA_ENCRYPTION_KEY_ID must match ${KEY_ID.pattern}"
        // Step 2: the active key goes into the map first, under that id.
        val keys = linkedMapOf<String, ByteArray>()
        decodeKey("DATA_ENCRYPTION_KEY", required("DATA_ENCRYPTION_KEY"))?.let { keys[activeId] = it }
        // Step 3: add each retired key (`id:base64`). A bad entry or a repeated id is recorded as an error.
        commaSeparated("DATA_ENCRYPTION_PREVIOUS_KEYS").forEach { entry ->
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
        /**
         * 32 bytes = 256 bits. HS256 signs with HMAC-SHA-256, and its key must be at least as long as the hash
         * output (RFC 7518, section 3.2).
         */
        const val MIN_JWT_SECRET_BYTES = 32

        /** AES-256 uses a key of exactly 256 bits = 32 bytes. */
        const val AES_256_KEY_BYTES = 32

        /** Shortest accepted `DEMO_PASSWORD` (docs/CONTRACTS.md, section 4). */
        const val MIN_DEMO_PASSWORD_LENGTH = 8

        /** Allowed `GEMINI_THINKING_LEVEL` values besides `none` (docs/CONTRACTS.md, section 4). */
        val GEMINI_THINKING_LEVELS = listOf("minimal", "low", "medium", "high")

        /** Key id: 1 to 16 letters, digits, '_' or '-'. It is the prefix of every ciphertext (docs/CONTRACTS.md). */
        val KEY_ID = Regex("[A-Za-z0-9_-]{1,16}")
    }
}
