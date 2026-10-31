package com.carevoice.documentation.infrastructure.alert

import com.carevoice.documentation.infrastructure.http.HttpClients
import com.carevoice.documentation.outbox.OutboxTestFixtures
import com.carevoice.documentation.outbox.PublishResult
import com.carevoice.documentation.resilience.CircuitBreakerSettings
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.ktor.client.HttpClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AlertServiceClient: POST /v1/events/observations")
class AlertServiceClientTest {
    private val wireMock = WireMockServer(wireMockConfig().dynamicPort())
    private lateinit var httpClient: HttpClient
    private lateinit var meterRegistry: SimpleMeterRegistry
    private val clock: Clock = Clock.fixed(OutboxTestFixtures.FIXED_NOW, ZoneOffset.UTC)

    @BeforeAll
    fun startServer() {
        wireMock.start()
        httpClient = HttpClients.create(REQUEST_TIMEOUT)
    }

    @AfterAll
    fun stopServer() {
        httpClient.close()
        wireMock.stop()
    }

    @BeforeEach
    fun reset() {
        wireMock.resetAll()
        meterRegistry = SimpleMeterRegistry()
    }

    private fun client(
        token: suspend () -> String = { TOKEN },
        breaker: CircuitBreakerSettings = CircuitBreakerSettings(),
    ) = AlertServiceClient(
        baseUrl = wireMock.baseUrl() + "/",
        httpClient = httpClient,
        serviceToken = token,
        meterRegistry = meterRegistry,
        circuitBreakerSettings = breaker,
        requestTimeout = REQUEST_TIMEOUT,
        clock = clock,
    )

    private fun respondWith(status: Int, retryAfter: String? = null) {
        val response = aResponse().withStatus(status)
        // 204 must not carry a body; every other status gets a small JSON one, like alert-service's answers.
        if (status != 204) response.withHeader("Content-Type", "application/json").withBody("{}")
        retryAfter?.let { response.withHeader("Retry-After", it) }
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(response))
    }

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @ParameterizedTest(name = "should classify HTTP {0} as NonRetryable")
        @ValueSource(ints = [400, 401, 403, 404, 409, 413, 415, 422])
        fun `should not retry client errors other than 429`(status: Int) {
            respondWith(status)
            assertThat(runBlocking { client().publish(OutboxTestFixtures.outboxEvent()) })
                .isEqualTo(PublishResult.NonRetryable("HTTP $status"))
        }

        @ParameterizedTest(name = "should classify HTTP {0} as Retryable")
        @ValueSource(ints = [500, 502, 504, 507])
        fun `should retry server errors`(status: Int) {
            respondWith(status)
            assertThat(runBlocking { client().publish(OutboxTestFixtures.outboxEvent()) })
                .isEqualTo(PublishResult.Retryable("HTTP $status"))
        }

        @ParameterizedTest(name = "should honour Retry-After in seconds on HTTP {0}")
        @ValueSource(ints = [429, 503])
        fun `should honour Retry-After seconds`(status: Int) {
            respondWith(status, retryAfter = "7")
            assertThat(runBlocking { client().publish(OutboxTestFixtures.outboxEvent()) })
                .isEqualTo(PublishResult.Retryable("HTTP $status", retryAfter = 7.seconds))
        }

        @Test
        fun `should honour Retry-After as an HTTP date`() {
            respondWith(503, retryAfter = "Sun, 25 Oct 2026 10:00:30 GMT")
            assertThat(runBlocking { client().publish(OutboxTestFixtures.outboxEvent()) })
                .isEqualTo(PublishResult.Retryable("HTTP 503", retryAfter = 30.seconds))
        }

        @ParameterizedTest(name = "should fall back to own backoff when Retry-After is \"{0}\"")
        @ValueSource(strings = ["soon", "-1", ""])
        fun `should ignore an unparseable Retry-After`(retryAfter: String) {
            respondWith(429, retryAfter = retryAfter)
            assertThat(runBlocking { client().publish(OutboxTestFixtures.outboxEvent()) })
                .isEqualTo(PublishResult.Retryable("HTTP 429", retryAfter = null))
        }

        @Test
        fun `should return Retryable on a timeout`() {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202).withFixedDelay(2_000)))
            assertThat(runBlocking { client().publish(OutboxTestFixtures.outboxEvent()) })
                .isEqualTo(PublishResult.Retryable("timeout"))
        }

        @ParameterizedTest(name = "should return Retryable on fault {0}")
        @EnumSource(
            value = Fault::class,
            names = ["CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE", "RANDOM_DATA_THEN_CLOSE"],
        )
        fun `should return Retryable on connection faults`(fault: Fault) {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(fault)))
            assertThat(runBlocking { client().publish(OutboxTestFixtures.outboxEvent()) })
                .isInstanceOf(PublishResult.Retryable::class.java)
        }

        @Test
        fun `should return Retryable when nothing listens on the port`() {
            val unreachable =
                AlertServiceClient("http://127.0.0.1:1", httpClient, {
                    TOKEN
                }, meterRegistry, requestTimeout = REQUEST_TIMEOUT)
            assertThat(runBlocking { unreachable.publish(OutboxTestFixtures.outboxEvent()) })
                .isInstanceOf(PublishResult.Retryable::class.java)
        }

        @Test
        fun `should return Retryable without calling when no service token can be obtained`() {
            val result =
                runBlocking {
                    client(token = { error("signing key unavailable") }).publish(OutboxTestFixtures.outboxEvent())
                }
            assertThat(result).isEqualTo(PublishResult.Retryable("service token unavailable"))
            assertThat(wireMock.allServeEvents).isEmpty()
        }
    }

    @Nested
    @DisplayName("Response Validation")
    inner class ResponseValidation {
        @ParameterizedTest(name = "should classify HTTP {0} as Published")
        @ValueSource(ints = [200, 202, 204])
        fun `should treat first delivery and duplicate as published`(status: Int) {
            respondWith(status)
            assertThat(
                runBlocking {
                    client().publish(OutboxTestFixtures.outboxEvent())
                },
            ).isEqualTo(PublishResult.Published)
        }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should send auth, idempotency and correlation headers with the stored payload unchanged`() {
            respondWith(202)
            val event = OutboxTestFixtures.outboxEvent()
            runBlocking { client().publish(event) }
            wireMock.verify(
                1,
                postRequestedFor(urlEqualTo(PATH))
                    .withHeader("Authorization", equalTo("Bearer $TOKEN"))
                    .withHeader("Idempotency-Key", equalTo(event.id.toString()))
                    .withHeader("X-Request-Id", equalTo(OutboxTestFixtures.REQUEST_ID))
                    .withHeader("traceparent", equalTo(OutboxTestFixtures.TRACEPARENT))
                    .withHeader("Content-Type", containing("application/json"))
                    .withRequestBody(equalToJson(event.payload)),
            )
            assertThat(wireMock.allServeEvents.single().request.bodyAsString).isEqualTo(event.payload)
        }

        @Test
        fun `should omit correlation headers when the outbox row has none`() {
            respondWith(202)
            runBlocking { client().publish(OutboxTestFixtures.outboxEvent(requestId = null, traceparent = null)) }
            wireMock.verify(
                1,
                postRequestedFor(urlEqualTo(PATH))
                    .withoutHeader("X-Request-Id")
                    .withoutHeader("traceparent"),
            )
        }

        @ParameterizedTest(name = "should drop a malformed requestId \"{0}\"")
        @ValueSource(strings = ["has space", "line\nbreak", "<script>", "umläut"])
        fun `should never forward a malformed requestId`(requestId: String) {
            respondWith(202)
            runBlocking {
                client().publish(
                    OutboxTestFixtures.outboxEvent(requestId = requestId, traceparent = "not-a-traceparent"),
                )
            }
            wireMock.verify(
                1,
                postRequestedFor(urlEqualTo(PATH)).withoutHeader("X-Request-Id").withoutHeader("traceparent"),
            )
        }

        @Test
        fun `should open the circuit after 50 percent failures over 20 calls and then stop calling`() {
            respondWith(500)
            val client = client()
            val results = runBlocking { List(20) { client.publish(OutboxTestFixtures.outboxEvent()) } }
            assertThat(results).allMatch { it is PublishResult.Retryable }
            assertThat(client.circuitBreaker.state).isEqualTo(CircuitBreaker.State.OPEN)

            val rejected = runBlocking { client.publish(OutboxTestFixtures.outboxEvent()) }

            assertThat(rejected).isEqualTo(PublishResult.CircuitOpen)
            assertThat(wireMock.allServeEvents).hasSize(20)
            assertThat(
                meterRegistry.find("resilience4j.circuitbreaker.state").tag("name", "alert-service").gauges(),
            ).isNotEmpty()
        }

        @Test
        fun `should not open the circuit for rejected events`() {
            respondWith(422)
            val client = client()
            runBlocking { repeat(25) { client.publish(OutboxTestFixtures.outboxEvent()) } }
            assertThat(client.circuitBreaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
        }
    }

    private companion object {
        const val PATH = "/v1/events/observations"
        const val TOKEN = "service.jwt.token"
        val REQUEST_TIMEOUT = 300.milliseconds
    }
}
