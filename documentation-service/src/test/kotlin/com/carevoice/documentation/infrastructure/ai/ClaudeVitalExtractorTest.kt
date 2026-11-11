package com.carevoice.documentation.infrastructure.ai

import com.carevoice.documentation.infrastructure.http.HttpClients
import com.carevoice.documentation.resilience.CircuitBreakerSettings
import com.carevoice.documentation.resilience.RetryPolicy
import com.carevoice.domain.extraction.RawVitalCandidate
import com.carevoice.domain.model.VitalType
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.ktor.client.HttpClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ClaudeVitalExtractor: POST /v1/messages")
class ClaudeVitalExtractorTest {
    private val wireMock = WireMockServer(wireMockConfig().dynamicPort())
    private lateinit var httpClient: HttpClient
    private lateinit var meterRegistry: SimpleMeterRegistry

    @BeforeAll
    fun startServer() {
        wireMock.start()
        httpClient = HttpClients.create()
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

    private fun extractor(breaker: CircuitBreakerSettings = CircuitBreakerSettings()) = ClaudeVitalExtractor(
        settings = AiSettings(
            apiKey = API_KEY,
            model = MODEL,
            // Generous for slow CI runners (a cold first request); the timeout test delays well beyond it.
            timeout = 1.seconds,
            baseUrl = wireMock.baseUrl(),
        ),
        httpClient = httpClient,
        meterRegistry = meterRegistry,
        circuitBreakerSettings = breaker,
        retryPolicy = RetryPolicy(maxAttempts = 2, base = 1.milliseconds, cap = 5.milliseconds, random = Random(3)),
    )

    private fun answerWith(modelText: String) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(messagesResponse(modelText))))
    }

    private fun fallbackCount(result: String): Double =
        meterRegistry.find("carevoice.ai.fallback").tag("result", result).counter()?.count() ?: 0.0

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @ParameterizedTest(name = "should return no candidates on HTTP {0}")
        @ValueSource(ints = [400, 401, 403, 404])
        fun `should not retry client errors`(status: Int) {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)))
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).isEmpty()
            assertThat(wireMock.allServeEvents).hasSize(1)
            assertThat(fallbackCount("error")).isEqualTo(1.0)
        }

        @ParameterizedTest(name = "should retry HTTP {0} once, then give up")
        @ValueSource(ints = [429, 500, 503, 529])
        fun `should retry transient errors exactly once`(status: Int) {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)))
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).isEmpty()
            assertThat(wireMock.allServeEvents).hasSize(2)
            assertThat(fallbackCount("error")).isEqualTo(1.0)
        }

        @Test
        fun `should return no candidates on a timeout without retrying`() {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(messagesResponse("[]")).withFixedDelay(3_000)))
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).isEmpty()
            assertThat(wireMock.allServeEvents).hasSize(1)
            assertThat(fallbackCount("timeout")).isEqualTo(1.0)
        }

        @Test
        fun `should return no candidates when the connection is reset`() {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).isEmpty()
            assertThat(fallbackCount("error")).isEqualTo(1.0)
        }

        @ParameterizedTest(name = "should treat model answer {0} as malformed")
        @ValueSource(strings = ["I could not find any vital signs.", "{not json", "```json\n[{\"type\":\n```", "42"])
        fun `should return no candidates for an unreadable answer`(modelText: String) {
            answerWith(modelText)
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).isEmpty()
            assertThat(fallbackCount("malformed")).isEqualTo(1.0)
        }

        @Test
        fun `should return no candidates when the body is not a Messages API response`() {
            wireMock.stubFor(
                post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("<html>proxy error</html>")),
            )
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).isEmpty()
            assertThat(fallbackCount("malformed")).isEqualTo(1.0)
        }

        @Test
        fun `should open its own circuit and then skip the model`() {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)))
            val extractor = extractor(CircuitBreakerSettings(slidingWindowSize = 4, minimumNumberOfCalls = 4))
            runBlocking { repeat(4) { extractor.extract(TRANSCRIPT) } }
            assertThat(extractor.circuitBreaker.state).isEqualTo(CircuitBreaker.State.OPEN)
            val callsBefore = wireMock.allServeEvents.size

            assertThat(runBlocking { extractor.extract(TRANSCRIPT) }).isEmpty()

            assertThat(wireMock.allServeEvents).hasSize(callsBefore)
            assertThat(fallbackCount("circuit_open")).isEqualTo(1.0)
        }
    }

    @Nested
    @DisplayName("Response Validation")
    inner class ResponseValidation {
        @ParameterizedTest(name = "should read {0}")
        @MethodSource("com.carevoice.documentation.infrastructure.ai.ClaudeVitalExtractorTest#answers")
        fun `should map model answers to candidates`(
            description: String,
            modelText: String,
            expected: List<RawVitalCandidate>,
        ) {
            answerWith(modelText)
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).containsExactlyElementsOf(expected)
        }

        @Test
        fun `should count an empty answer as empty, not as an error`() {
            answerWith("[]")
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).isEmpty()
            assertThat(fallbackCount("empty")).isEqualTo(1.0)
            assertThat(fallbackCount("error")).isZero()
        }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should send the API key, version, model and the transcript inside transcript tags`() {
            answerWith("[]")
            runBlocking { extractor().extract(TRANSCRIPT) }
            wireMock.verify(
                1,
                postRequestedFor(urlEqualTo(PATH))
                    .withHeader("x-api-key", equalTo(API_KEY))
                    .withHeader("anthropic-version", equalTo("2023-06-01"))
                    .withHeader("Content-Type", containing("application/json"))
                    .withRequestBody(matchingJsonPath("$.model", equalTo(MODEL)))
                    .withRequestBody(matchingJsonPath("$.temperature", equalTo("0")))
                    .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                    .withRequestBody(
                        matchingJsonPath("$.messages[0].content", equalTo("<transcript>\n$TRANSCRIPT\n</transcript>")),
                    ),
            )
        }

        @Test
        fun `should succeed when the retry after a 500 succeeds`() {
            wireMock.stubFor(
                post(urlEqualTo(PATH))
                    .inScenario("flaky")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(500))
                    .willSetStateTo("recovered"),
            )
            wireMock.stubFor(
                post(urlEqualTo(PATH))
                    .inScenario("flaky")
                    .whenScenarioStateIs("recovered")
                    .willReturn(okJson(messagesResponse("""[{"type":"PULSE","value":72}]"""))),
            )
            assertThat(
                runBlocking {
                    extractor().extract(TRANSCRIPT)
                },
            ).containsExactly(RawVitalCandidate(VitalType.PULSE, 72.0))
            assertThat(wireMock.allServeEvents).hasSize(2)
            assertThat(fallbackCount("extracted")).isEqualTo(1.0)
        }

        @Test
        fun `should record the request duration as a percentile histogram`() {
            answerWith("""[{"type":"PULSE","value":72}]""")
            runBlocking { extractor().extract(TRANSCRIPT) }
            val timer = meterRegistry.find("carevoice.ai.request").tag("result", "extracted").timer()
            assertThat(timer).isNotNull()
            assertThat(timer!!.count()).isEqualTo(1)
            assertThat(timer.takeSnapshot().histogramCounts()).isNotEmpty()
        }

        @Test
        fun `should not call the model for a blank transcript`() {
            assertThat(runBlocking { extractor().extract("   ") }).isEmpty()
            assertThat(wireMock.allServeEvents).isEmpty()
        }
    }

    companion object {
        private const val PATH = "/v1/messages"
        private const val API_KEY = "test-api-key"
        private const val MODEL = "claude-haiku-5-5"
        private const val TRANSCRIPT = "resident seems tired, pulse about seventy two, will check again later"

        private fun okJson(body: String) =
            aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)

        /** A Messages API response whose single text block is [modelText]. */
        fun messagesResponse(modelText: String): String = buildJsonObject {
            put("id", "msg_01")
            put("type", "message")
            put("role", "assistant")
            put("model", MODEL)
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", modelText)
                }
            }
            put("stop_reason", "end_turn")
        }.toString()

        @JvmStatic
        fun answers(): List<Arguments> = listOf(
            Arguments.of(
                "a plain array",
                """[{"type":"BLOOD_PRESSURE","value":182,"secondaryValue":95,"unit":null},{"type":"PULSE","value":72}]""",
                listOf(
                    RawVitalCandidate(VitalType.BLOOD_PRESSURE, 182.0, 95.0),
                    RawVitalCandidate(VitalType.PULSE, 72.0),
                ),
            ),
            Arguments.of(
                "a fenced array with prose around it",
                "Here you go:\n```json\n[{\"type\":\"BODY_TEMPERATURE\",\"value\":38.4,\"unit\":\"C\"}]\n```\nDone.",
                listOf(RawVitalCandidate(VitalType.BODY_TEMPERATURE, 38.4, unit = "C")),
            ),
            Arguments.of(
                "a single object instead of an array",
                """{"type":"pulse","value":"88"}""",
                listOf(RawVitalCandidate(VitalType.PULSE, 88.0)),
            ),
            Arguments.of(
                "malformed entries next to a good one",
                """[{"type":"OXYGEN","value":97},{"type":"BLOOD_PRESSURE","value":120},{"value":1},"x",{"type":"PULSE","value":64}]""",
                listOf(RawVitalCandidate(VitalType.PULSE, 64.0)),
            ),
        )
    }
}
