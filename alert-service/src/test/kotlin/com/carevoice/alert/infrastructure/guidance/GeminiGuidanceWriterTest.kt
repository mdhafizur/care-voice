package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.GuidanceStep
import com.carevoice.alert.domain.guidance.ProtocolChunk
import com.carevoice.alert.domain.guidance.ScoredChunk
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The parts of the Gemini adapter that decide correctness: the request (prompt, schema, thinking) and reading the
 * answer. As for Claude, the use case's grounding check guards whatever comes back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("GeminiGuidanceWriter")
class GeminiGuidanceWriterTest {
    private val httpClient = HttpClient(CIO)
    private val writer = GeminiGuidanceWriter(GeminiSettings(apiKey = "test-key"), httpClient)

    @AfterAll
    fun close() {
        httpClient.close()
    }

    @Nested
    @DisplayName("Request")
    inner class Request {
        @Test
        fun `should send the shared rules and every excerpt with its citation id`() {
            val body = request()

            assertThat(body.text("systemInstruction")).isEqualTo(GuidancePrompt.GEMINI_SYSTEM)
            assertThat(GuidancePrompt.GEMINI_SYSTEM).contains("ONLY the protocol excerpts").contains("doses")
            assertThat(body.text("contents"))
                .contains("Alert: Low blood pressure. Severity: WARNING.")
                .contains("<excerpt id=\"hypotension#2\"")
        }

        @Test
        fun `should only allow the citation ids of the excerpts sent`() {
            val schema = request().generationConfig().getValue("responseJsonSchema").jsonObject
            val step = schema.path("properties", "steps", "items").jsonObject
            val citationIds =
                step.path("properties", "citations", "items", "enum").jsonArray.map { it.jsonPrimitive.content }

            assertThat(citationIds).containsExactly("hypotension#1", "hypotension#2")
            val maxSteps = schema.path("properties", "steps", "maxItems").jsonPrimitive.int
            assertThat(maxSteps).isEqualTo(GuidancePrompt.MAX_STEPS)
        }

        @Test
        fun `should ask for JSON at a low thinking level`() {
            val config = request().generationConfig()

            assertThat(config.getValue("responseMimeType").jsonPrimitive.content).isEqualTo("application/json")
            assertThat(config.path("thinkingConfig", "thinkingLevel").jsonPrimitive.content).isEqualTo("low")
        }

        @Test
        fun `should send no thinking configuration when the level is none`() {
            val noThinking = GeminiGuidanceWriter(GeminiSettings(apiKey = "test-key", thinkingLevel = null), httpClient)
            val body = Json.parseToJsonElement(noThinking.requestBody(SITUATION, SOURCES)).jsonObject

            assertThat(body.generationConfig()).doesNotContainKey("thinkingConfig")
        }

        @Test
        fun `should build the endpoint from the model name, with or without the models prefix`() {
            val base = "https://generativelanguage.googleapis.com"
            val expected = "$base/v1beta/models/gemini-flash-latest:generateContent"

            assertThat(GeminiApi.url(base, "gemini-flash-latest", "generateContent")).isEqualTo(expected)
            assertThat(GeminiApi.url("$base/", "models/gemini-flash-latest", "generateContent")).isEqualTo(expected)
        }
    }

    @Nested
    @DisplayName("Answer")
    inner class Answer {
        @Test
        fun `should read the answer part and skip thought summaries`() {
            val answer = """{"steps": [{"text": "Help the resident lie down.", "citations": ["hypotension#2"]}]}"""
            val body =
                buildJsonObject {
                    putJsonArray("candidates") {
                        addJsonObject {
                            putJsonObject("content") {
                                put("role", "model")
                                putJsonArray("parts") {
                                    addJsonObject {
                                        put("text", "Thinking about the protocol...")
                                        put("thought", true)
                                    }
                                    addJsonObject {
                                        put("text", answer)
                                        put("thoughtSignature", "c2ln")
                                    }
                                }
                            }
                            put("finishReason", "STOP")
                        }
                    }
                }.toString()

            val steps = GuidancePrompt.parse(GeminiApi.textOf(body)!!)

            assertThat(steps).containsExactly(GuidanceStep("Help the resident lie down.", listOf("hypotension#2")))
        }

        @Test
        fun `should find no text when the prompt was blocked or the answer was cut off`() {
            assertThat(GeminiApi.textOf("""{"promptFeedback": {"blockReason": "SAFETY"}}""")).isNull()
            assertThat(GeminiApi.textOf("""{"candidates": [{"finishReason": "MAX_TOKENS"}]}""")).isNull()
            assertThat(GeminiApi.textOf("<html>proxy error</html>")).isNull()
        }

        @Test
        fun `should read an empty step list as declined`() {
            // write() maps this to null, the same "declined" Claude expresses with NO_GUIDANCE.
            assertThat(GuidancePrompt.parse("""{"steps": []}""")).isEmpty()
            assertThat(GuidancePrompt.GEMINI_SYSTEM).contains("""return {"steps": []}""")
        }
    }

    private fun request(): JsonObject = Json.parseToJsonElement(writer.requestBody(SITUATION, SOURCES)).jsonObject

    private fun JsonObject.generationConfig(): JsonObject = getValue("generationConfig").jsonObject

    /** The text of `<key>.parts[0].text`, or `<key>[0].parts[0].text` for the contents array. */
    private fun JsonObject.text(key: String): String {
        val node = getValue(key)
        val holder = if (node is JsonArray) node.first().jsonObject else node.jsonObject
        return holder.getValue("parts").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
    }

    private fun JsonObject.path(vararg keys: String) =
        keys.drop(1).fold(getValue(keys.first())) { node, key -> node.jsonObject.getValue(key) }

    private companion object {
        const val SITUATION = "Low blood pressure. Severity: WARNING."

        val SOURCES =
            listOf(1 to "Assess", 2 to "Immediate steps").map { (n, section) ->
                ScoredChunk(
                    ProtocolChunk(
                        id = "hypotension#$n",
                        documentId = "hypotension",
                        documentTitle = "Protocol 4.3: Hypotension",
                        section = section,
                        text = "Protocol 4.3: Hypotension. $section.\n- Help the resident lie down.",
                    ),
                    0.5,
                )
            }
    }
}
