package com.carevoice.alert.config

import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

/**
 * Collects every error instead of throwing on the first, so an operator fixes the deployment in one round trip.
 */
internal class ConfigLoader(private val env: Map<String, String>) {
    private val errors = mutableListOf<String>()

    /** Reads and checks every value; throws ConfigException with all errors when anything is missing or invalid. */
    fun load(): AppConfig {
        // HTTP port and MongoDB.
        // 65_535 is the highest TCP port number.
        val httpPort = int("HTTP_PORT", AppConfig.DEFAULT_HTTP_PORT, 1..65_535)
        val mongoUri = required("MONGO_URI")
        if (mongoUri != null) validateMongoUri(mongoUri)
        val mongoDatabase = optional("MONGO_DATABASE") ?: AppConfig.DEFAULT_MONGO_DATABASE
        // MongoDB database names must be shorter than 64 characters
        // (https://www.mongodb.com/docs/manual/reference/limits/#naming-restrictions).
        // This service also allows only letters, digits, `_` and `-`.
        if (!mongoDatabase.matches(Regex("[A-Za-z0-9_-]{1,63}"))) {
            errors += "MONGO_DATABASE must match [A-Za-z0-9_-]{1,63}"
        }
        val mongoPoolSize = int("MONGO_POOL_SIZE", AppConfig.DEFAULT_MONGO_POOL_SIZE, 1..500)
        val migrateOnStartup = boolean("MONGO_MIGRATE_ON_STARTUP", default = true)

        // Login tokens (JWT): the secret must be long enough.
        val jwtSecret = required("JWT_SECRET")?.toByteArray(Charsets.UTF_8)
        if (jwtSecret != null && jwtSecret.size < AppConfig.MIN_JWT_SECRET_BYTES) {
            errors += "JWT_SECRET must be at least ${AppConfig.MIN_JWT_SECRET_BYTES} bytes"
        }
        val jwtIssuer = optional("JWT_ISSUER") ?: AppConfig.DEFAULT_JWT_ISSUER
        val jwtAudience = optional("JWT_AUDIENCE") ?: AppConfig.DEFAULT_JWT_AUDIENCE

        // RabbitMQ (the message broker), limits, timeouts and guidance.
        val rabbitUrl = required("RABBITMQ_URL")
        if (rabbitUrl != null) validateAmqpUrl(rabbitUrl)
        val prefetch = int("RABBITMQ_PREFETCH", AppConfig.DEFAULT_RABBITMQ_PREFETCH, 1..1_000)
        val rateLimit = int("RATE_LIMIT_PER_MINUTE", AppConfig.DEFAULT_RATE_LIMIT_PER_MINUTE, 1..100_000)
        // Default 30_000 ms, the same as AppConfig.DEFAULT_REQUEST_TIMEOUT (30 s); allowed are 1 s to 2 min.
        val requestTimeoutMs = int("REQUEST_TIMEOUT_MS", 30_000, 1_000..120_000)
        val guidance = guidance()

        // Report every problem at once. After this check every value is set, so the `!!` below are safe.
        if (errors.isNotEmpty()) throw ConfigException(errors.toList())
        return AppConfig(
            httpPort = httpPort!!,
            mongo = MongoConfig(mongoUri!!, mongoDatabase, mongoPoolSize!!, migrateOnStartup!!),
            jwt = JwtConfig(jwtSecret!!, jwtIssuer, jwtAudience),
            rabbitmq = RabbitMqConfig(rabbitUrl!!, prefetch!!),
            rateLimitPerMinute = rateLimit!!,
            guidance = guidance,
            requestTimeout = requestTimeoutMs!!.milliseconds,
        )
    }

    /**
     * Reads the guidance settings. An API key is required only for a provider that is actually used.
     * An unknown name is recorded as an error and a default is kept, so loading goes on and finds more errors.
     */
    private fun guidance(): GuidanceConfig {
        // Embedder: the service that turns text into vectors (lists of numbers) for the protocol search.
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
        // Writer: whether generated guidance is on, which provider writes it, and its key.
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
        // Gemini key: needed when Gemini embeds or writes.
        val geminiApiKey = optional("GEMINI_API_KEY")
        val geminiWrites = aiEnabled && aiProvider == GuidanceConfig.AiProvider.GEMINI
        val geminiNeeded = embedder == GuidanceConfig.EmbedderKind.GEMINI || geminiWrites
        if (geminiNeeded && geminiApiKey == null) {
            errors += "GEMINI_API_KEY is required when GUIDANCE_EMBEDDER=gemini or AI_PROVIDER=gemini"
        }
        // Model names fall back to their defaults when they are not set.
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

    /** Returns the trimmed value, or null when the variable is unset or blank. */
    private fun optional(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

    /** Like [optional], but a missing value is also recorded as an error. */
    private fun required(name: String): String? = optional(name) ?: null.also { errors += "$name is required" }

    /** Parses an integer inside [range]; [default] when unset. An invalid value is recorded and gives null. */
    private fun int(name: String, default: Int, range: IntRange): Int? {
        val raw = optional(name) ?: return default
        val value = raw.toIntOrNull()
        if (value == null || value !in range) {
            errors += "$name must be an integer in ${range.first}..${range.last} (was '$raw')"
            return null
        }
        return value
    }

    /** Accepts only `true` or `false` (any case); [default] when unset. Anything else is recorded and gives null. */
    private fun boolean(name: String, default: Boolean): Boolean? = when (optional(name)?.lowercase()) {
        null -> default
        "true" -> true
        "false" -> false
        else -> null.also { errors += "$name must be 'true' or 'false'" }
    }

    /** Records an error unless the URI starts with `mongodb://` or `mongodb+srv://`. */
    private fun validateMongoUri(uri: String) {
        if (!uri.startsWith("mongodb://") && !uri.startsWith("mongodb+srv://")) {
            errors += "MONGO_URI must start with mongodb:// or mongodb+srv://"
        }
    }

    /** Records an error unless the URL scheme is `amqp` or `amqps` (the RabbitMQ protocol). */
    private fun validateAmqpUrl(url: String) {
        val scheme = runCatching { URI(url).scheme }.getOrNull()
        if (scheme != "amqp" && scheme != "amqps") errors += "RABBITMQ_URL must be an amqp:// or amqps:// URL"
    }
}
