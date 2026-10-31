package com.carevoice.alert.api

import com.carevoice.alert.support.AlertTestApp
import com.carevoice.alert.support.SwitchableHealthCheck
import com.carevoice.alert.support.TestFixtures
import com.carevoice.testsupport.assertJsonEquals
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

@DisplayName("GET /livez")
class LivenessEndpointTest {
    private val app = AlertTestApp.forClass(javaClass)

    @Nested
    @DisplayName("Response Validation")
    inner class ResponseValidation {
        @Test
        fun `should return 200 UP without authentication`() = app.run {
            val response = client.get("/livez")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertJsonEquals("""{"status":"UP","checks":{}}""", response.bodyAsText())
            TestFixtures.openApi.assertResponseMatches("/livez", "get", 200, response.bodyAsText())
        }

        @Test
        fun `should echo a valid X-Request-Id`() = app.run {
            val response = client.get("/livez") { header(HttpHeaders.XRequestId, "req-123.abc:1") }

            assertThat(response.headers[HttpHeaders.XRequestId]).isEqualTo("req-123.abc:1")
        }

        @Test
        fun `should set the security headers`() = app.run {
            val response = client.get("/livez")

            assertThat(response.headers["X-Content-Type-Options"]).isEqualTo("nosniff")
            assertThat(response.headers["Referrer-Policy"]).isEqualTo("no-referrer")
            assertThat(response.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
        }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @ParameterizedTest(name = "should replace X-Request-Id {0}")
        @FieldSource("com.carevoice.alert.support.AlertTestValues#invalidRequestIds")
        fun `should replace an invalid X-Request-Id with a generated one`(invalid: String) = app.run {
            // Values with line breaks cannot be sent as a header at all; the client rejects them before the server.
            val response =
                runCatching { client.get("/livez") { header(HttpHeaders.XRequestId, invalid) } }.getOrNull()
                    ?: return@run

            val echoed = response.headers[HttpHeaders.XRequestId]
            assertThat(echoed).isNotEqualTo(invalid)
            assertThat(echoed).matches("[A-Za-z0-9._:-]{1,64}")
        }
    }
}

@DisplayName("GET /readyz")
class ReadinessEndpointTest {
    private val app = AlertTestApp.forClass(javaClass)

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @Test
        fun `should return 503 with Retry-After when MongoDB is down`() {
            val mongo = SwitchableHealthCheck("mongo", healthy = false)
            val rabbit = SwitchableHealthCheck("rabbitmq")
            app.run(app.dependencies(healthChecks = listOf(mongo, rabbit))) {
                val response = client.get("/readyz")

                assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
                assertThat(response.headers[HttpHeaders.RetryAfter]).isEqualTo("5")
                assertJsonEquals(
                    """{"status":"DOWN","checks":{"mongo":"DOWN","rabbitmq":"UP"}}""",
                    response.bodyAsText(),
                )
                TestFixtures.openApi.assertResponseMatches("/readyz", "get", 503, response.bodyAsText())
            }
        }

        @Test
        fun `should return 503 when the RabbitMQ consumer is disconnected`() {
            val mongo = SwitchableHealthCheck("mongo")
            val rabbit = SwitchableHealthCheck("rabbitmq", healthy = false)
            app.run(app.dependencies(healthChecks = listOf(mongo, rabbit))) {
                val response = client.get("/readyz")

                assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
                assertJsonEquals(
                    """{"status":"DOWN","checks":{"mongo":"UP","rabbitmq":"DOWN"}}""",
                    response.bodyAsText(),
                )
                TestFixtures.openApi.assertResponseMatches("/readyz", "get", 503, response.bodyAsText())
            }
        }
    }

    @Nested
    @DisplayName("Response Validation")
    inner class ResponseValidation {
        @Test
        fun `should return 200 UP when every dependency is up`() = app.run {
            val response = client.get("/readyz")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.RetryAfter]).isNull()
            assertJsonEquals(
                """{"status":"UP","checks":{"mongo":"UP","rabbitmq":"UP"}}""",
                response.bodyAsText(),
            )
            TestFixtures.openApi.assertResponseMatches("/readyz", "get", 200, response.bodyAsText())
        }
    }
}
