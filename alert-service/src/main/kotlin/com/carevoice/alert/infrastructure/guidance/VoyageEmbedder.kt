package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.Embedder
import com.carevoice.alert.domain.guidance.Vectors
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Semantic embeddings from Voyage AI (the embedding provider Anthropic's documentation recommends; Claude itself has no
 * embedding endpoint). Enabled with `GUIDANCE_EMBEDDER=voyage` and `VOYAGE_API_KEY`.
 *
 * Asymmetric retrieval: documents and queries are embedded with `input_type` "document" / "query", which the model
 * uses to place short questions near the passages that answer them.
 */
class VoyageEmbedder(
    private val apiKey: String,
    private val httpClient: HttpClient,
    override val model: String = DEFAULT_MODEL,
    override val minScore: Double = DEFAULT_MIN_SCORE,
    private val timeout: Duration = 10.seconds,
    baseUrl: String = "https://api.voyageai.com",
) : Embedder {
    private val endpoint = baseUrl.trimEnd('/') + "/v1/embeddings"

    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
    }

    /** Embeds the protocol chunks with input type "document"; an empty list makes no API call. */
    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
        if (texts.isEmpty()) emptyList() else embed(texts, "document")

    /** Embeds the alert text with input type "query". */
    override suspend fun embedQuery(text: String): FloatArray = embed(listOf(text), "query").single()

    /**
     * Sends all [texts] in one request and returns the vectors in the order of [texts].
     * The answer is sorted by its `index` field, because the order of `data` is not relied on.
     */
    private suspend fun embed(texts: List<String>, inputType: String): List<FloatArray> {
        // Step 1: build the request: all texts in one call, with the model and the input type.
        val body =
            buildJsonObject {
                putJsonArray("input") { texts.forEach { add(it) } }
                put("model", model)
                put("input_type", inputType)
            }
        // Step 2: send it with a time limit and the API key as a bearer token (the `Authorization` header).
        val response =
            httpClient.post(endpoint) {
                timeout { requestTimeoutMillis = timeout.inWholeMilliseconds }
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        // Step 3: log the answer, and fail on any HTTP status that is not a success.
        val text = response.bodyAsText()
        logProviderResponse("voyage", model, "embed_$inputType", response.status.value, text)
        check(response.status.isSuccess()) { "Voyage embeddings failed with HTTP ${response.status.value}" }
        // Step 4: read one vector per text, back in the order of the input.
        val data = Json.parseToJsonElement(text).jsonObject.getValue("data").jsonArray
        return data
            .map { it.jsonObject }
            .sortedBy { it.getValue("index").jsonPrimitive.int }
            .map { item -> toVector(item.getValue("embedding").jsonArray) }
    }

    /** Turns a JSON array of numbers into a vector of length 1 (normalized). */
    private fun toVector(values: JsonArray): FloatArray =
        Vectors.normalize(FloatArray(values.size) { values[it].jsonPrimitive.float })

    /** Hides the API key, so the embedder can be logged safely. */
    override fun toString(): String = "VoyageEmbedder(model=$model, apiKey=***)"

    companion object {
        const val DEFAULT_MODEL = "voyage-3.5-lite"

        /** Semantic scores are higher overall than lexical ones; tune with the evaluation set when changing models. */
        const val DEFAULT_MIN_SCORE = 0.35
    }
}
