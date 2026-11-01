package com.carevoice.documentation.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

@DisplayName("AppConfig: request deadline")
class RequestTimeoutConfigTest {
    @Test
    fun `should default to 30 seconds and accept REQUEST_TIMEOUT_MS`() {
        assertThat(AppConfig.load(validEnv()).requestTimeout).isEqualTo(30.seconds)
        assertThat(AppConfig.load(validEnv() + ("REQUEST_TIMEOUT_MS" to "45000")).requestTimeout)
            .isEqualTo(45.seconds)
    }

    @Test
    fun `should reject a deadline outside 1 to 120 seconds`() {
        assertThatThrownBy { AppConfig.load(validEnv() + ("REQUEST_TIMEOUT_MS" to "500")) }
            .isInstanceOfSatisfying(ConfigException::class.java) {
                assertThat(it.errors).containsExactly("REQUEST_TIMEOUT_MS must be an integer in 1000..120000")
            }
    }

    private fun validEnv() = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://localhost:5432/carevoice",
        "DATABASE_USER" to "carevoice",
        "DATABASE_PASSWORD" to "password",
        "JWT_SECRET" to "j".repeat(32),
        "DATA_ENCRYPTION_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
        "REDIS_URL" to "redis://localhost:6379",
        "RABBITMQ_URL" to "amqp://guest:guest@localhost:5672/carevoice",
    )
}
