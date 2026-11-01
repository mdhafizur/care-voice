package com.carevoice.alert.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@DisplayName("AppConfig")
class AppConfigTest {
    private fun validEnv(): Map<String, String> = mapOf(
        "MONGO_URI" to "mongodb://user:secret@mongo:27017",
        "JWT_SECRET" to "x".repeat(32),
        "RABBITMQ_URL" to "amqp://user:pass@rabbitmq:5672/carevoice",
    )

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @Test
        fun `should list every missing required variable at once`() {
            val error = catchThrowableOfType(ConfigException::class.java) { AppConfig.load(emptyMap()) }

            assertThat(error.errors).containsExactlyInAnyOrder(
                "MONGO_URI is required",
                "JWT_SECRET is required",
                "RABBITMQ_URL is required",
            )
        }

        @Test
        fun `should collect every invalid value instead of stopping at the first`() {
            val env =
                validEnv() +
                    mapOf(
                        "HTTP_PORT" to "70000",
                        "MONGO_URI" to "postgres://db",
                        "MONGO_DATABASE" to "bad name",
                        "MONGO_POOL_SIZE" to "0",
                        "MONGO_MIGRATE_ON_STARTUP" to "maybe",
                        "JWT_SECRET" to "too-short",
                        "RABBITMQ_URL" to "http://rabbit",
                        "RABBITMQ_PREFETCH" to "abc",
                        "RATE_LIMIT_PER_MINUTE" to "-5",
                        "REQUEST_TIMEOUT_MS" to "500",
                    )

            val error = catchThrowableOfType(ConfigException::class.java) { AppConfig.load(env) }

            assertThat(error.errors).containsExactlyInAnyOrder(
                "HTTP_PORT must be an integer in 1..65535 (was '70000')",
                "MONGO_URI must start with mongodb:// or mongodb+srv://",
                "MONGO_DATABASE must match [A-Za-z0-9_-]{1,63}",
                "MONGO_POOL_SIZE must be an integer in 1..500 (was '0')",
                "MONGO_MIGRATE_ON_STARTUP must be 'true' or 'false'",
                "JWT_SECRET must be at least 32 bytes",
                "RABBITMQ_URL must be an amqp:// or amqps:// URL",
                "RABBITMQ_PREFETCH must be an integer in 1..1000 (was 'abc')",
                "RATE_LIMIT_PER_MINUTE must be an integer in 1..100000 (was '-5')",
                "REQUEST_TIMEOUT_MS must be an integer in 1000..120000 (was '500')",
            )
        }

        @Test
        fun `should treat a blank required variable as missing`() {
            val env = validEnv() + ("JWT_SECRET" to "  ")

            val error = catchThrowableOfType(ConfigException::class.java) { AppConfig.load(env) }

            assertThat(error.errors).containsExactly("JWT_SECRET is required")
        }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should apply the documented defaults`() {
            val config = AppConfig.load(validEnv())

            assertThat(config.httpPort).isEqualTo(8081)
            assertThat(config.mongo.database).isEqualTo("carevoice_alerts")
            assertThat(config.mongo.poolSize).isEqualTo(20)
            assertThat(config.mongo.migrateOnStartup).isTrue()
            assertThat(config.jwt.issuer).isEqualTo("carevoice-auth")
            assertThat(config.jwt.audience).isEqualTo("carevoice")
            assertThat(config.rabbitmq.prefetch).isEqualTo(32)
            assertThat(config.rateLimitPerMinute).isEqualTo(120)
            assertThat(config.requestTimeout).isEqualTo(30.seconds)
        }

        @Test
        fun `should never print secrets or credentials`() {
            val printed = AppConfig.load(validEnv()).toString()

            assertThat(printed).doesNotContain("secret@", "user:pass", "x".repeat(32))
            assertThat(printed).contains("***")
        }
    }

    @Nested
    @DisplayName("Gemini")
    inner class Gemini {
        @Test
        fun `should require the Gemini key for Gemini embeddings or Gemini guidance, not the Claude key`() {
            val embedding = validEnv() + ("GUIDANCE_EMBEDDER" to "gemini")
            val writing = validEnv() + mapOf("GUIDANCE_AI_ENABLED" to "true", "AI_PROVIDER" to "gemini")
            val expected = "GEMINI_API_KEY is required when GUIDANCE_EMBEDDER=gemini or AI_PROVIDER=gemini"

            listOf(embedding, writing).forEach { env ->
                val error = catchThrowableOfType(ConfigException::class.java) { AppConfig.load(env) }
                assertThat(error.errors).containsExactly(expected)
            }
        }

        @Test
        fun `should use the Gemini defaults and keep the key out of toString`() {
            val env =
                validEnv() +
                    mapOf(
                        "GUIDANCE_EMBEDDER" to "gemini",
                        "GUIDANCE_AI_ENABLED" to "true",
                        "AI_PROVIDER" to "gemini",
                        "GEMINI_API_KEY" to "gemini-secret-key",
                        // Claude's model variable must not leak into the Gemini request.
                        "AI_MODEL" to "claude-haiku-5-5",
                    )
            val guidance = AppConfig.load(env).guidance

            assertThat(guidance.embedder).isEqualTo(GuidanceConfig.EmbedderKind.GEMINI)
            assertThat(guidance.aiProvider).isEqualTo(GuidanceConfig.AiProvider.GEMINI)
            assertThat(guidance.geminiModel).isEqualTo("gemini-flash-latest")
            assertThat(guidance.geminiEmbeddingModel).isEqualTo("gemini-embedding-001")
            assertThat(guidance.geminiThinkingLevel).isEqualTo("low")
            assertThat(guidance.toString()).doesNotContain("gemini-secret-key")
        }

        @Test
        fun `should reject an unknown provider and thinking level`() {
            val env = validEnv() + mapOf("AI_PROVIDER" to "openai", "GEMINI_THINKING_LEVEL" to "max")

            val error = catchThrowableOfType(ConfigException::class.java) { AppConfig.load(env) }

            assertThat(error.errors).hasSize(2)
            assertThat(error.errors).anySatisfy { assertThat(it).startsWith("AI_PROVIDER must be") }
            assertThat(error.errors).anySatisfy { assertThat(it).startsWith("GEMINI_THINKING_LEVEL must be") }
        }
    }
}
