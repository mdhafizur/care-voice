package com.carevoice.documentation.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64

@DisplayName("AppConfig: trusted proxies")
class TrustedProxiesConfigTest {
    @Test
    fun `should trust no proxy by default`() {
        assertThat(AppConfig.load(validEnv()).trustedProxies).isEmpty()
    }

    @Test
    fun `should read a comma-separated list of networks`() {
        val config = AppConfig.load(validEnv() + ("TRUSTED_PROXIES" to "10.42.0.0/16, fd00::/8"))

        assertThat(config.trustedProxies.map { it.text }).containsExactly("10.42.0.0/16", "fd00::/8")
    }

    @Test
    fun `should report every invalid entry`() {
        assertThatThrownBy { AppConfig.load(validEnv() + ("TRUSTED_PROXIES" to "10.0.0.0/8,traefik,10.0.0.0/40")) }
            .isInstanceOfSatisfying(ConfigException::class.java) {
                assertThat(it.errors).containsExactly(
                    "TRUSTED_PROXIES: 'traefik' is not an IP address or CIDR network",
                    "TRUSTED_PROXIES: '10.0.0.0/40' is not an IP address or CIDR network",
                )
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
