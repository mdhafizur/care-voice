package com.carevoice.documentation.api

import com.carevoice.documentation.config.IpNetwork
import com.carevoice.documentation.config.parseIpLiteral
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("Client address behind trusted proxies")
class ClientAddressTest {
    private val traefik = listOf(IpNetwork.parse("172.16.0.0/12")!!)

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should use the connection's address when no proxy is trusted`() {
            assertThat(clientAddress("172.18.0.5", listOf("203.0.113.7"), emptyList())).isEqualTo("172.18.0.5")
        }

        @Test
        fun `should take the client from X-Forwarded-For when the connection comes from a trusted proxy`() {
            assertThat(clientAddress("172.18.0.5", listOf("203.0.113.7"), traefik)).isEqualTo("203.0.113.7")
        }

        @Test
        fun `should ignore X-Forwarded-For from a client that connects directly`() {
            // An attacker sending a new fake address with every login attempt would otherwise never be limited.
            assertThat(clientAddress("198.51.100.9", listOf("203.0.113.7"), traefik)).isEqualTo("198.51.100.9")
        }

        @Test
        fun `should read the chain from the right and skip every trusted proxy`() {
            // client (spoofed first entry), real client, inner proxy; the connection comes from the outer proxy.
            val chain = listOf("1.2.3.4, 203.0.113.7", "172.20.0.3")

            assertThat(clientAddress("172.18.0.5", chain, traefik)).isEqualTo("203.0.113.7")
        }

        @Test
        fun `should fall back to the nearest proxy when an entry is not an IP address`() {
            assertThat(clientAddress("172.18.0.5", listOf("203.0.113.7, unknown"), traefik)).isEqualTo("172.18.0.5")
        }

        @Test
        fun `should strip ports and understand IPv6`() {
            val networks = traefik + IpNetwork.parse("fd00::/8")!!

            assertThat(clientAddress("172.18.0.5", listOf("203.0.113.7:51234"), networks)).isEqualTo("203.0.113.7")
            assertThat(clientAddress("fd00::1", listOf("[2001:db8::7]:443"), networks))
                .isEqualTo("2001:db8:0:0:0:0:0:7")
        }
    }

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = ["10.0.0.0/8", "172.16.0.0/12", "192.168.1.10", "fd00::/8", "::1"])
        fun `should accept IP addresses and CIDR networks`(text: String) {
            assertThat(IpNetwork.parse(text)).isNotNull
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(
            strings = ["10.0.0.0/33", "256.1.1.1", "10.0.0/8", "example.com", "fe80::1%eth0", "", "10.0.0.0/x"],
        )
        fun `should reject anything that is not an IP literal and prefix`(text: String) {
            assertThat(IpNetwork.parse(text)).isNull()
        }

        @Test
        fun `should match addresses by prefix`() {
            val network = IpNetwork.parse("172.16.0.0/12")!!

            assertThat(network.contains(parseIpLiteral("172.31.255.255")!!)).isTrue()
            assertThat(network.contains(parseIpLiteral("172.32.0.1")!!)).isFalse()
            assertThat(network.contains(parseIpLiteral("::ffff:1")!!)).isFalse()
        }

        @Test
        fun `should never resolve a hostname from the header`() {
            assertThat(parseIpLiteral("localhost")).isNull()
            assertThat(parseIpLiteral("attacker.example")).isNull()
        }
    }
}
