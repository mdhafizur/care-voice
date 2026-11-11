package com.carevoice.documentation.infrastructure.ai

import com.carevoice.documentation.infrastructure.http.HttpClients
import com.carevoice.documentation.resilience.CircuitBreakerSettings
import com.carevoice.documentation.resilience.RetryPolicy
import com.carevoice.domain.extraction.RawVitalCandidate
import com.carevoice.domain.model.VitalType
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.ktor.client.HttpClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The Gemini wire format. Retries, the circuit breaker and the answer parsing are shared with Claude
 * ([HttpAiVitalExtractor], [VitalAnswerParser]) and covered in depth by [ClaudeVitalExtractorTest]; here they are
 * checked once each, to prove the Gemini adapter is plugged into them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("GeminiVitalExtractor: POST /v1beta/models/{model}:generateContent")
class GeminiVitalExtractorTest {
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

    private fun extractor(model: String = MODEL, thinkingLevel: String? = "low") = GeminiVitalExtractor(
        settings = AiSettings(
            apiKey = API_KEY,
            model = model,
            // Generous for slow CI runners (a cold first request); the timeout test delays well beyond it.
            timeout = 1.seconds,
            baseUrl = wireMock.baseUrl(),
            thinkingLevel = thinkingLevel,
        ),
        httpClient = httpClient,
        meterRegistry = meterRegistry,
        circuitBreakerSettings = CircuitBreakerSettings(),
        retryPolicy = RetryPolicy(maxAttempts = 2, base = 1.milliseconds, cap = 5.milliseconds, random = Random(3)),
    )

    private fun answerWith(body: String) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(body)))
    }

    private fun fallbackCount(result: String): Double =
        meterRegistry.find("carevoice.ai.fallback").tag("result", result).counter()?.count() ?: 0.0

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @ParameterizedTest(name = "should return no candidates on HTTP {0} without retrying")
        @ValueSource(ints = [400, 403, 404])
        fun `should not retry client errors`(status: Int) {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)))
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).isEmpty()
            assertThat(wireMock.allServeEvents).hasSize(1)
            assertThat(fallbackCount("error")).isEqualTo(1.0)
        }

        @ParameterizedTest(name = "should retry HTTP {0} once, then give up")
        @ValueSource(ints = [429, 500, 503])
        fun `should retry transient errors exactly once`(status: Int) {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)))
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).isEmpty()
            assertThat(wireMock.allServeEvents).hasSize(2)
        }

        @Test
        fun `should treat a blocked prompt without candidates as malformed`() {
            answerWith("""{"promptFeedback": {"blockReason": "SAFETY"}}""")
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).isEmpty()
            assertThat(fallbackCount("malformed")).isEqualTo(1.0)
        }

        @Test
        fun `should treat an answer cut off before any text as malformed`() {
            answerWith("""{"candidates": [{"content": {"role": "model"}, "finishReason": "MAX_TOKENS"}]}""")
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).isEmpty()
            assertThat(fallbackCount("malformed")).isEqualTo(1.0)
        }
    }

    @Nested
    @DisplayName("Response Validation")
    inner class ResponseValidation {
        @Test
        fun `should read the candidates from the answer text`() {
            answerWith(
                generateContentResponse(
                    """[{"type":"BLOOD_PRESSURE","value":182,"secondaryValue":95,"unit":null},""" +
                        """{"type":"PULSE","value":72,"secondaryValue":null,"unit":null}]""",
                ),
            )
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).containsExactly(
                RawVitalCandidate(VitalType.BLOOD_PRESSURE, 182.0, 95.0),
                RawVitalCandidate(VitalType.PULSE, 72.0),
            )
            assertThat(fallbackCount("extracted")).isEqualTo(1.0)
        }

        @Test
        fun `should ignore thought summaries and read only the answer parts`() {
            answerWith(
                generateContentResponse(
                    """[{"type":"PULSE","value":64,"secondaryValue":null,"unit":null}]""",
                    thought = "The nurse said pulse 99 earlier [this is reasoning, not the answer]",
                ),
            )
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) })
                .containsExactly(RawVitalCandidate(VitalType.PULSE, 64.0))
        }

        @Test
        fun `should count an empty array as empty, not as an error`() {
            answerWith(generateContentResponse("[]"))
            assertThat(runBlocking { extractor().extract(TRANSCRIPT) }).isEmpty()
            assertThat(fallbackCount("empty")).isEqualTo(1.0)
        }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should send the key in a header, the shared prompt and a JSON schema for the answer`() {
            answerWith(generateContentResponse("[]"))
            runBlocking { extractor().extract(TRANSCRIPT) }
            wireMock.verify(
                1,
                postRequestedFor(urlEqualTo(PATH))
                    .withHeader("x-goog-api-key", equalTo(API_KEY))
                    .withQueryParam("key", absent()),
            )

            val body = sentBody()
            assertThat(body.at("systemInstruction", "parts", 0, "text")).isEqualTo(VitalExtractionPrompt.SYSTEM)
            assertThat(body.at("contents", 0, "role")).isEqualTo("user")
            assertThat(body.at("contents", 0, "parts", 0, "text")).isEqualTo("<transcript>\n$TRANSCRIPT\n</transcript>")
            val generation = body.getValue("generationConfig").jsonObject
            assertThat(generation.at("responseMimeType")).isEqualTo("application/json")
            assertThat(generation.at("responseJsonSchema", "type")).isEqualTo("array")
            assertThat(generation.at("thinkingConfig", "thinkingLevel")).isEqualTo("low")
            // Gemini 3 models are meant to run at their default temperature.
            assertThat(generation).doesNotContainKey("temperature")
        }

        @Test
        fun `should send no thinking configuration when the level is none`() {
            answerWith(generateContentResponse("[]"))
            runBlocking { extractor(thinkingLevel = null).extract(TRANSCRIPT) }

            assertThat(sentBody().getValue("generationConfig").jsonObject).doesNotContainKey("thinkingConfig")
        }

        @Test
        fun `should accept the model name with or without the models prefix`() {
            answerWith(generateContentResponse("[]"))
            runBlocking { extractor(model = "models/$MODEL").extract(TRANSCRIPT) }
            wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)))
        }
    }

    private fun sentBody(): JsonObject =
        Json.parseToJsonElement(wireMock.allServeEvents.single().request.bodyAsString).jsonObject

    /** The string at [path] (keys and array indexes), or null when any step is missing. */
    private fun JsonObject.at(vararg path: Any): String? {
        var node: JsonElement? = this
        for (step in path) {
            node = when (step) {
                is Int -> (node as? JsonArray)?.getOrNull(step)
                else -> (node as? JsonObject)?.get(step as String)
            }
        }
        return (node as? JsonPrimitive)?.contentOrNull
    }

    companion object {
        private const val API_KEY = "test-gemini-key"
        private const val MODEL = "gemini-flash-latest"
        private const val PATH = "/v1beta/models/$MODEL:generateContent"
        private const val TRANSCRIPT = "resident seems tired, pulse about sixty four, will check again later"

        private fun okJson(body: String) =
            aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)

        /** A `generateContent` response whose answer part is [modelText], optionally after a thought summary. */
        fun generateContentResponse(modelText: String, thought: String? = null): String = buildJsonObject {
            putJsonArray("candidates") {
                addJsonObject {
                    putJsonObject("content") {
                        put("role", "model")
                        putJsonArray("parts") {
                            if (thought != null) {
                                addJsonObject {
                                    put("text", thought)
                                    put("thought", true)
                                }
                            }
                            addJsonObject {
                                put("text", modelText)
                                put("thoughtSignature", "c2lnbmF0dXJl")
                            }
                        }
                    }
                    put("finishReason", "STOP")
                    put("index", 0)
                }
            }
            putJsonObject("usageMetadata") {
                put("promptTokenCount", 120)
                put("candidatesTokenCount", 30)
                put("thoughtsTokenCount", 40)
            }
            put("modelVersion", "gemini-3.8-flash")
        }.toString()
    }
}
