package com.carevoice.alert.config

import java.net.URI

/**
 * The complete, typed configuration of alert-service (docs/CONTRACTS.md, section 4).
 *
 * Why one immutable object loaded once at startup instead of reading `System.getenv` where a value is needed: every
 * value is parsed and validated in one place BEFORE anything opens a connection, and a misconfigured pod fails
 * immediately with a list of every problem ("fail fast, all errors at once") instead of crashing on the first request
 * or message that happens to touch the missing value. Everything downstream receives plain typed values through
 * constructors, which is also what makes the service testable without environment variables.
 */
data class AppConfig(
    val httpPort: Int,
    val mongo: MongoConfig,
    val jwt: JwtConfig,
    val rabbitmq: RabbitMqConfig,
    val rateLimitPerMinute: Int,
    val guidance: GuidanceConfig = GuidanceConfig(),
) {
    companion object {
        const val DEFAULT_HTTP_PORT = 8081
        const val DEFAULT_MONGO_DATABASE = "carevoice_alerts"
        const val DEFAULT_MONGO_POOL_SIZE = 20
        const val DEFAULT_JWT_ISSUER = "carevoice-auth"
        const val DEFAULT_JWT_AUDIENCE = "carevoice"
        const val DEFAULT_RABBITMQ_PREFETCH = 32
        const val DEFAULT_RATE_LIMIT_PER_MINUTE = 120
        const val MIN_JWT_SECRET_BYTES = 32

        /**
         * Loads and validates the configuration from [env] (normally `System.getenv()`).
         *
         * @throws ConfigException listing EVERY missing or invalid value, not just the first one.
         */
        fun load(env: Map<String, String>): AppConfig = ConfigLoader(env).load()
    }
}

/**
 * @property poolSize maximum connections per Mongo server in this pod's pool.
 * @property migrateOnStartup when false the app only checks that the database schema matches the code and refuses to
 *   start otherwise; migrations are then applied by the `migrate` one-shot (Kubernetes pre-upgrade Job).
 */
data class MongoConfig(val uri: String, val database: String, val poolSize: Int, val migrateOnStartup: Boolean) {
    override fun toString(): String =
        "MongoConfig(uri=${redactUserInfo(uri)}, database=$database, poolSize=$poolSize, " +
            "migrateOnStartup=$migrateOnStartup)"
}

data class JwtConfig(val secret: ByteArray, val issuer: String, val audience: String) {
    override fun equals(other: Any?): Boolean =
        other is JwtConfig && secret.contentEquals(other.secret) && issuer == other.issuer && audience == other.audience

    override fun hashCode(): Int = arrayOf<Any>(secret.contentHashCode(), issuer, audience).contentHashCode()

    /** Never prints the secret, so the config can be logged at startup and appear in failed assertions safely. */
    override fun toString(): String = "JwtConfig(secret=***, issuer=$issuer, audience=$audience)"
}

/**
 * @property prefetch maximum unacknowledged deliveries per consumer (`basicQos`): broker-side backpressure.
 */
data class RabbitMqConfig(val url: String, val prefetch: Int) {
    override fun toString(): String = "RabbitMqConfig(url=${redactUserInfo(url)}, prefetch=$prefetch)"
}

/**
 * Retrieval and generation settings of the alert guidance RAG (docs/CONTRACTS.md, section 9c).
 *
 * @property embedder `hashing` (local, deterministic), `voyage` (needs [voyageApiKey]) or `gemini` (needs
 *   [geminiApiKey]).
 * @property aiEnabled when true, an LLM phrases the guidance: [aiProvider] Claude (needs [anthropicApiKey], model
 *   [aiModel]) or Gemini (needs [geminiApiKey], model [geminiModel]); otherwise the protocol's own steps are copied.
 * @property geminiThinkingLevel `GEMINI_THINKING_LEVEL` (default `low`; `none` sends no thinking configuration).
 */
data class GuidanceConfig(
    val embedder: EmbedderKind = EmbedderKind.HASHING,
    val voyageApiKey: String? = null,
    val voyageModel: String = DEFAULT_VOYAGE_MODEL,
    val aiEnabled: Boolean = false,
    val anthropicApiKey: String? = null,
    val aiModel: String = DEFAULT_AI_MODEL,
    val aiProvider: AiProvider = AiProvider.CLAUDE,
    val geminiApiKey: String? = null,
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val geminiEmbeddingModel: String = DEFAULT_GEMINI_EMBEDDING_MODEL,
    val geminiThinkingLevel: String? = DEFAULT_GEMINI_THINKING_LEVEL,
) {
    enum class EmbedderKind { HASHING, VOYAGE, GEMINI }

    /** Which LLM writes the guidance (`AI_PROVIDER`, shared with documentation-service). */
    enum class AiProvider { CLAUDE, GEMINI }

    override fun toString(): String =
        "GuidanceConfig(embedder=$embedder, voyageApiKey=${voyageApiKey?.let { "***" }}, voyageModel=$voyageModel, " +
            "aiEnabled=$aiEnabled, aiProvider=$aiProvider, anthropicApiKey=${anthropicApiKey?.let { "***" }}, " +
            "aiModel=$aiModel, geminiApiKey=${geminiApiKey?.let { "***" }}, geminiModel=$geminiModel, " +
            "geminiEmbeddingModel=$geminiEmbeddingModel, geminiThinkingLevel=$geminiThinkingLevel)"

    companion object {
        const val DEFAULT_VOYAGE_MODEL = "voyage-3.5-lite"
        const val DEFAULT_AI_MODEL = "claude-haiku-5-5"
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
        const val DEFAULT_GEMINI_EMBEDDING_MODEL = "gemini-embedding-001"
        const val DEFAULT_GEMINI_THINKING_LEVEL = "low"
        val GEMINI_THINKING_LEVELS = listOf("minimal", "low", "medium", "high")
    }
}

/** Thrown at startup when the environment is incomplete or invalid; [errors] lists every problem. */
class ConfigException(val errors: List<String>) :
    RuntimeException("Invalid configuration:\n" + errors.joinToString("\n") { " - $it" })

/**
 * Collects every error instead of throwing on the first, so an operator fixes the deployment in one round trip.
 */
private class ConfigLoader(private val env: Map<String, String>) {
    private val errors = mutableListOf<String>()

    fun load(): AppConfig {
        val httpPort = int("HTTP_PORT", AppConfig.DEFAULT_HTTP_PORT, 1..65_535)
        val mongoUri = required("MONGO_URI")
        if (mongoUri != null && !mongoUri.startsWith("mongodb://") && !mongoUri.startsWith("mongodb+srv://")) {
            errors += "MONGO_URI must start with mongodb:// or mongodb+srv://"
        }
        val mongoDatabase = optional("MONGO_DATABASE") ?: AppConfig.DEFAULT_MONGO_DATABASE
        if (!mongoDatabase.matches(Regex("[A-Za-z0-9_-]{1,63}"))) {
            errors += "MONGO_DATABASE must match [A-Za-z0-9_-]{1,63}"
        }
        val mongoPoolSize = int("MONGO_POOL_SIZE", AppConfig.DEFAULT_MONGO_POOL_SIZE, 1..500)
        val migrateOnStartup = boolean("MONGO_MIGRATE_ON_STARTUP", default = true)

        val jwtSecret = required("JWT_SECRET")?.toByteArray(Charsets.UTF_8)
        if (jwtSecret != null && jwtSecret.size < AppConfig.MIN_JWT_SECRET_BYTES) {
            errors += "JWT_SECRET must be at least ${AppConfig.MIN_JWT_SECRET_BYTES} bytes"
        }
        val jwtIssuer = optional("JWT_ISSUER") ?: AppConfig.DEFAULT_JWT_ISSUER
        val jwtAudience = optional("JWT_AUDIENCE") ?: AppConfig.DEFAULT_JWT_AUDIENCE

        val rabbitUrl = required("RABBITMQ_URL")
        if (rabbitUrl != null) validateAmqpUrl(rabbitUrl)
        val prefetch = int("RABBITMQ_PREFETCH", AppConfig.DEFAULT_RABBITMQ_PREFETCH, 1..1_000)
        val rateLimit = int("RATE_LIMIT_PER_MINUTE", AppConfig.DEFAULT_RATE_LIMIT_PER_MINUTE, 1..100_000)
        val guidance = guidance()

        if (errors.isNotEmpty()) throw ConfigException(errors.toList())
        return AppConfig(
            httpPort = httpPort!!,
            mongo = MongoConfig(mongoUri!!, mongoDatabase, mongoPoolSize!!, migrateOnStartup!!),
            jwt = JwtConfig(jwtSecret!!, jwtIssuer, jwtAudience),
            rabbitmq = RabbitMqConfig(rabbitUrl!!, prefetch!!),
            rateLimitPerMinute = rateLimit!!,
            guidance = guidance,
        )
    }

    private fun guidance(): GuidanceConfig {
        val embedder =
            when (optional("GUIDANCE_EMBEDDER")?.lowercase()) {
                null, "hashing" -> GuidanceConfig.EmbedderKind.HASHING

                "voyage" -> GuidanceConfig.EmbedderKind.VOYAGE

                "gemini" -> GuidanceConfig.EmbedderKind.GEMINI

                else -> GuidanceConfig.EmbedderKind.HASHING.also {
                    errors += "GUIDANCE_EMBEDDER must be 'hashing', 'voyage' or 'gemini'"
                }
            }
        val voyageApiKey = optional("VOYAGE_API_KEY")
        if (embedder == GuidanceConfig.EmbedderKind.VOYAGE && voyageApiKey == null) {
            errors += "VOYAGE_API_KEY is required when GUIDANCE_EMBEDDER=voyage"
        }
        val aiEnabled = boolean("GUIDANCE_AI_ENABLED", default = false) ?: false
        val aiProvider =
            when (optional("AI_PROVIDER")?.lowercase()) {
                null, "claude" -> GuidanceConfig.AiProvider.CLAUDE
                "gemini" -> GuidanceConfig.AiProvider.GEMINI
                else -> GuidanceConfig.AiProvider.CLAUDE.also { errors += "AI_PROVIDER must be 'claude' or 'gemini'" }
            }
        val anthropicApiKey = optional("ANTHROPIC_API_KEY")
        if (aiEnabled && aiProvider == GuidanceConfig.AiProvider.CLAUDE && anthropicApiKey == null) {
            errors += "ANTHROPIC_API_KEY is required when GUIDANCE_AI_ENABLED=true"
        }
        val geminiApiKey = optional("GEMINI_API_KEY")
        val geminiWrites = aiEnabled && aiProvider == GuidanceConfig.AiProvider.GEMINI
        val geminiNeeded = embedder == GuidanceConfig.EmbedderKind.GEMINI || geminiWrites
        if (geminiNeeded && geminiApiKey == null) {
            errors += "GEMINI_API_KEY is required when GUIDANCE_EMBEDDER=gemini or AI_PROVIDER=gemini"
        }
        return GuidanceConfig(
            embedder = embedder,
            voyageApiKey = voyageApiKey,
            voyageModel = optional("VOYAGE_MODEL") ?: GuidanceConfig.DEFAULT_VOYAGE_MODEL,
            aiEnabled = aiEnabled,
            anthropicApiKey = anthropicApiKey,
            aiModel = optional("AI_MODEL") ?: GuidanceConfig.DEFAULT_AI_MODEL,
            aiProvider = aiProvider,
            geminiApiKey = geminiApiKey,
            geminiModel = optional("GEMINI_MODEL") ?: GuidanceConfig.DEFAULT_GEMINI_MODEL,
            geminiEmbeddingModel = optional("GEMINI_EMBEDDING_MODEL") ?: GuidanceConfig.DEFAULT_GEMINI_EMBEDDING_MODEL,
            geminiThinkingLevel = geminiThinkingLevel(),
        )
    }

    /** `low` by default (the lowest level Gemini 3.8 Flash accepts); `none` omits thinkingConfig. */
    private fun geminiThinkingLevel(): String? = when (val level = optional("GEMINI_THINKING_LEVEL")?.lowercase()) {
        null -> GuidanceConfig.DEFAULT_GEMINI_THINKING_LEVEL

        "none" -> null

        in GuidanceConfig.GEMINI_THINKING_LEVELS -> level

        else -> {
            val levels = GuidanceConfig.GEMINI_THINKING_LEVELS.joinToString()
            errors += "GEMINI_THINKING_LEVEL must be one of $levels or none"
            GuidanceConfig.DEFAULT_GEMINI_THINKING_LEVEL
        }
    }

    private fun optional(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

    private fun required(name: String): String? = optional(name) ?: null.also { errors += "$name is required" }

    private fun int(name: String, default: Int, range: IntRange): Int? {
        val raw = optional(name) ?: return default
        val value = raw.toIntOrNull()
        if (value == null || value !in range) {
            errors += "$name must be an integer in ${range.first}..${range.last} (was '$raw')"
            return null
        }
        return value
    }

    private fun boolean(name: String, default: Boolean): Boolean? = when (optional(name)?.lowercase()) {
        null -> default
        "true" -> true
        "false" -> false
        else -> null.also { errors += "$name must be 'true' or 'false'" }
    }

    private fun validateAmqpUrl(url: String) {
        val scheme = runCatching { URI(url).scheme }.getOrNull()
        if (scheme != "amqp" && scheme != "amqps") errors += "RABBITMQ_URL must be an amqp:// or amqps:// URL"
    }
}

/** Replaces `user:password@` in a connection string so it can be logged. */
internal fun redactUserInfo(url: String): String = url.replace(Regex("(?<=://)[^@/]+@"), "***@")
