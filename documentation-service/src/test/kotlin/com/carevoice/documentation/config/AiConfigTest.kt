package com.carevoice.documentation.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

@DisplayName("AppConfig: AI fallback provider")
class AiConfigTest {
    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @Test
        fun `should require the key of the selected provider when the fallback is enabled`() {
            val env = validEnv() + mapOf("AI_FALLBACK_ENABLED" to "true", "AI_PROVIDER" to "gemini")

            assertThatThrownBy { AppConfig.load(env + ("ANTHROPIC_API_KEY" to "sk-ant-x")) }
                .isInstanceOfSatisfying(ConfigException::class.java) {
                    assertThat(it.errors).containsExactly("GEMINI_API_KEY is required")
                }
        }

        @Test
        fun `should reject an unknown provider and an unknown thinking level`() {
            val env = validEnv() + mapOf("AI_PROVIDER" to "openai")

            assertThatThrownBy { AppConfig.load(env) }
                .isInstanceOfSatisfying(ConfigException::class.java) {
                    assertThat(it.errors).containsExactly("AI_PROVIDER must be 'claude' or 'gemini'")
                }
            assertThatThrownBy { AppConfig.load(validEnv() + gemini() + ("GEMINI_THINKING_LEVEL" to "max")) }
                .isInstanceOfSatisfying(ConfigException::class.java) {
                    assertThat(it.errors.single()).startsWith("GEMINI_THINKING_LEVEL must be one of")
                }
        }

        @Test
        fun `should reject an AI timeout outside 500 to 30000 ms`() {
            assertThatThrownBy { AppConfig.load(validEnv() + gemini() + ("AI_TIMEOUT_MS" to "60000")) }
                .isInstanceOfSatisfying(ConfigException::class.java) {
                    assertThat(it.errors.single()).startsWith("AI_TIMEOUT_MS")
                }
        }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should default to Claude with its own model variable`() {
            val ai = AppConfig.load(validEnv() + ("AI_MODEL" to "claude-sonnet-5-5")).ai

            assertThat(ai.provider).isEqualTo(AiProvider.CLAUDE)
            assertThat(ai.model).isEqualTo("claude-sonnet-5-5")
            assertThat(ai.thinkingLevel).isNull()
        }

        @Test
        fun `should never send the Claude model name to Gemini`() {
            // .env.example sets AI_MODEL for Claude; switching the provider must not carry it over.
            val ai = AppConfig.load(validEnv() + gemini() + ("AI_MODEL" to "claude-haiku-5-5")).ai

            assertThat(ai.provider).isEqualTo(AiProvider.GEMINI)
            assertThat(ai.apiKey).isEqualTo(GEMINI_KEY)
            assertThat(ai.model).isEqualTo("gemini-flash-latest")
            assertThat(ai.thinkingLevel).isEqualTo("low")
        }

        @Test
        fun `should send no thinking configuration when the level is none`() {
            val ai = AppConfig.load(validEnv() + gemini() + ("GEMINI_THINKING_LEVEL" to "none")).ai

            assertThat(ai.thinkingLevel).isNull()
        }

        @Test
        fun `should use the provider default timeout unless AI_TIMEOUT_MS is set`() {
            assertThat(AppConfig.load(validEnv() + gemini()).ai.timeout).isNull()
            assertThat(AppConfig.load(validEnv() + gemini() + ("AI_TIMEOUT_MS" to "12000")).ai.timeout)
                .isEqualTo(12.seconds)
        }

        @Test
        fun `should never print the API key`() {
            val printed = AppConfig.load(validEnv() + gemini()).toString()

            assertThat(printed).doesNotContain(GEMINI_KEY).contains("provider=GEMINI")
        }
    }

    private companion object {
        const val GEMINI_KEY = "gemini-test-key-123"

        fun gemini() = mapOf("AI_FALLBACK_ENABLED" to "true", "AI_PROVIDER" to "gemini", "GEMINI_API_KEY" to GEMINI_KEY)

        fun validEnv() = mapOf(
            "DATABASE_URL" to "jdbc:postgresql://localhost:5432/carevoice",
            "DATABASE_USER" to "carevoice",
            "DATABASE_PASSWORD" to "password",
            "JWT_SECRET" to "j".repeat(32),
            "DATA_ENCRYPTION_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            "REDIS_URL" to "redis://localhost:6379",
            "RABBITMQ_URL" to "amqp://guest:guest@localhost:5672/carevoice",
        )
    }
}
