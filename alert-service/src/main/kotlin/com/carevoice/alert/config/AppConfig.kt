package com.carevoice.alert.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
    val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
) {
    companion object {
        const val DEFAULT_HTTP_PORT = 8081
        const val DEFAULT_MONGO_DATABASE = "carevoice_alerts"
        const val DEFAULT_MONGO_POOL_SIZE = 20
        const val DEFAULT_JWT_ISSUER = "carevoice-auth"
        const val DEFAULT_JWT_AUDIENCE = "carevoice"
        const val DEFAULT_RABBITMQ_PREFETCH = 32
        const val DEFAULT_RATE_LIMIT_PER_MINUTE = 120

        /**
         * HS256 needs a key of at least 256 bits (32 bytes): RFC 7518, section 3.2,
         * https://www.rfc-editor.org/rfc/rfc7518#section-3.2
         */
        const val MIN_JWT_SECRET_BYTES = 32

        val DEFAULT_REQUEST_TIMEOUT: Duration = 30.seconds

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
    /** Hides the user and password in the URI, so the config can be logged safely. */
    override fun toString(): String =
        "MongoConfig(uri=${redactUserInfo(uri)}, database=$database, poolSize=$poolSize, " +
            "migrateOnStartup=$migrateOnStartup)"
}

data class JwtConfig(val secret: ByteArray, val issuer: String, val audience: String) {
    /** Compares the secret by content; the generated data-class equals would only compare array references. */
    override fun equals(other: Any?): Boolean =
        other is JwtConfig && secret.contentEquals(other.secret) && issuer == other.issuer && audience == other.audience

    /** Hashes the secret by content, so it stays consistent with equals. */
    override fun hashCode(): Int = arrayOf<Any>(secret.contentHashCode(), issuer, audience).contentHashCode()

    /** Never prints the secret, so the config can be logged at startup and appear in failed assertions safely. */
    override fun toString(): String = "JwtConfig(secret=***, issuer=$issuer, audience=$audience)"
}

/**
 * @property prefetch maximum unacknowledged deliveries per consumer (`basicQos`): broker-side backpressure.
 */
data class RabbitMqConfig(val url: String, val prefetch: Int) {
    /** Hides the user and password in the URL, so the config can be logged safely. */
    override fun toString(): String = "RabbitMqConfig(url=${redactUserInfo(url)}, prefetch=$prefetch)"
}

/** Thrown at startup when the environment is incomplete or invalid; [errors] lists every problem. */
class ConfigException(val errors: List<String>) :
    RuntimeException("Invalid configuration:\n" + errors.joinToString("\n") { " - $it" })

/**
 * Replaces `user:password@` in a connection string so it can be logged. The regex matches the text right after
 * `://` up to and including the next `@`, as long as that text has no `/` (so an `@` in the path is never touched).
 */
internal fun redactUserInfo(url: String): String = url.replace(Regex("(?<=://)[^@/]+@"), "***@")
