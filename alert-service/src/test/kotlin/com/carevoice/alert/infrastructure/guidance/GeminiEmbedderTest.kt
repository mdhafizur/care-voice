package com.carevoice.alert.infrastructure.guidance

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.sqrt

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("GeminiEmbedder")
class GeminiEmbedderTest {
    private val httpClient = HttpClient(CIO)

    @AfterAll
    fun close() {
        httpClient.close()
    }

    private fun embedder(model: String = "gemini-embedding-001") =
        GeminiEmbedder(apiKey = "test-key", httpClient = httpClient, apiModel = model)

    private fun requests(embedder: GeminiEmbedder, purpose: GeminiEmbedder.Purpose): List<JsonObject> = Json
        .parseToJsonElement(embedder.requestBody(listOf("Recheck the blood pressure."), purpose))
        .jsonObject
        .getValue("requests")
        .jsonArray
        .map { it.jsonObject }

    private fun JsonObject.text(): String {
        val part = getValue("content").jsonObject.getValue("parts").jsonArray.first().jsonObject
        return part.getValue("text").jsonPrimitive.content
    }

    @Nested
    @DisplayName("Request")
    inner class Request {
        @Test
        fun `should mark documents and queries with the task type for gemini-embedding-001`() {
            val document = requests(embedder(), GeminiEmbedder.Purpose.DOCUMENT).single()
            val query = requests(embedder(), GeminiEmbedder.Purpose.QUERY).single()

            assertThat(document.getValue("model").jsonPrimitive.content).isEqualTo("models/gemini-embedding-001")
            assertThat(document.getValue("taskType").jsonPrimitive.content).isEqualTo("RETRIEVAL_DOCUMENT")
            assertThat(query.getValue("taskType").jsonPrimitive.content).isEqualTo("RETRIEVAL_QUERY")
            assertThat(document.getValue("outputDimensionality").jsonPrimitive.int).isEqualTo(768)
            assertThat(document.text()).isEqualTo("Recheck the blood pressure.")
        }

        @Test
        fun `should put the task into the text for models without task types`() {
            val embedder = embedder(model = "gemini-embedding-2")
            val document = requests(embedder, GeminiEmbedder.Purpose.DOCUMENT).single()
            val query = requests(embedder, GeminiEmbedder.Purpose.QUERY).single()

            assertThat(document).doesNotContainKey("taskType")
            assertThat(document.text()).isEqualTo("title: none | text: Recheck the blood pressure.")
            assertThat(query.text()).isEqualTo("task: search result | query: Recheck the blood pressure.")
        }

        @Test
        fun `should name the vector space with model and dimensions`() {
            // Stored chunks are keyed by this, so a dimension change re-embeds instead of mixing vector spaces.
            assertThat(embedder().model).isEqualTo("gemini-embedding-001@768")
            assertThat(embedder().toString()).doesNotContain("test-key")
        }
    }

    @Nested
    @DisplayName("Response")
    inner class Response {
        @Test
        fun `should return one normalized vector per text, in request order`() {
            val vectors =
                GeminiEmbedder.vectorsOf("""{"embeddings": [{"values": [3.0, 4.0]}, {"values": [0.0, 2.0]}]}""")

            assertThat(vectors).hasSize(2)
            assertThat(vectors[0][0]).isCloseTo(0.6f, within(1e-6f))
            assertThat(vectors[0][1]).isCloseTo(0.8f, within(1e-6f))
            assertThat(vectors[1][1]).isCloseTo(1.0f, within(1e-6f))
            vectors.forEach { vector ->
                assertThat(sqrt(vector.sumOf { (it * it).toDouble() })).isCloseTo(1.0, within(1e-6))
            }
        }
    }
}
