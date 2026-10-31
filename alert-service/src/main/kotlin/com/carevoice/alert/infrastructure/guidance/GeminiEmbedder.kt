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
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Semantic embeddings from the Gemini API (`POST /v1beta/models/{model}:batchEmbedContents`), enabled with
 * `GUIDANCE_EMBEDDER=gemini` and `GEMINI_API_KEY`.
 *
 * Asymmetric retrieval, as with Voyage: protocol chunks are embedded as documents, the alert as a query.
 * `gemini-embedding-001` takes that as `taskType` (`RETRIEVAL_DOCUMENT` / `RETRIEVAL_QUERY`); newer models
 * (`gemini-embedding-2`) take it as an instruction in the text instead (`title: none | text: ...`,
 * `task: search result | query: ...`), so both styles are supported.
 *
 * Vectors are requested at [dimensions] (768: a quarter of the storage of the full 3072, with little loss) and
 * normalized here, because `gemini-embedding-001` returns truncated vectors unnormalized. [model] includes the
 * dimension count: the stored corpus is keyed by it, so changing [dimensions] re-embeds instead of comparing vectors
 * of different spaces.
 */
class GeminiEmbedder(
    private val apiKey: String,
    private val httpClient: HttpClient,
    private val apiModel: String = GeminiApi.DEFAULT_EMBEDDING_MODEL,
    private val dimensions: Int = DEFAULT_DIMENSIONS,
    override val minScore: Double = DEFAULT_MIN_SCORE,
    private val timeout: Duration = 10.seconds,
    baseUrl: String = GeminiApi.DEFAULT_BASE_URL,
) : Embedder {
    override val model: String = "${apiModel.removePrefix("models/")}@$dimensions"

    private val endpoint = GeminiApi.url(baseUrl, apiModel, "batchEmbedContents")

    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(dimensions in 128..3072) { "dimensions must be in 128..3072" }
    }

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
        texts.chunked(MAX_BATCH).flatMap { embed(it, Purpose.DOCUMENT) }

    override suspend fun embedQuery(text: String): FloatArray = embed(listOf(text), Purpose.QUERY).single()

    private suspend fun embed(texts: List<String>, purpose: Purpose): List<FloatArray> {
        val response =
            httpClient.post(endpoint) {
                timeout { requestTimeoutMillis = timeout.inWholeMilliseconds }
                header(GeminiApi.API_KEY_HEADER, apiKey)
                setBody(TextContent(requestBody(texts, purpose), ContentType.Application.Json))
            }
        val body = response.bodyAsText()
        logProviderResponse("gemini", model, "embed_${purpose.name.lowercase()}", response.status.value, body)
        check(response.status.isSuccess()) { "Gemini embeddings failed with HTTP ${response.status.value}" }
        val vectors = vectorsOf(body)
        check(vectors.size == texts.size) { "Gemini returned ${vectors.size} embeddings for ${texts.size} texts" }
        return vectors
    }

    fun requestBody(texts: List<String>, purpose: Purpose): String = buildJsonObject {
        putJsonArray("requests") {
            texts.forEach { text ->
                addJsonObject {
                    put("model", "models/" + apiModel.removePrefix("models/"))
                    putJsonObject("content") {
                        putJsonArray("parts") { addJsonObject { put("text", prepared(text, purpose)) } }
                    }
                    if (usesTaskType) put("taskType", purpose.taskType)
                    put("outputDimensionality", dimensions)
                }
            }
        }
    }.toString()

    private val usesTaskType: Boolean
        get() = apiModel.removePrefix("models/").let { it == "gemini-embedding-001" || it.startsWith("text-embedding") }

    private fun prepared(text: String, purpose: Purpose): String = when {
        usesTaskType -> text
        purpose == Purpose.DOCUMENT -> "title: none | text: $text"
        else -> "task: search result | query: $text"
    }

    override fun toString(): String = "GeminiEmbedder(model=$model, apiKey=***)"

    enum class Purpose(val taskType: String) {
        DOCUMENT("RETRIEVAL_DOCUMENT"),
        QUERY("RETRIEVAL_QUERY"),
    }

    companion object {
        const val DEFAULT_DIMENSIONS = 768

        /**
         * Starting point; semantic scores sit higher overall than the hashing embedder's lexical ones. Tune it with the
         * retrieval evaluation set against the real API when changing the model (docs/CONTRACTS.md, section 9c).
         */
        const val DEFAULT_MIN_SCORE = 0.5

        /** Requests per `batchEmbedContents` call. */
        const val MAX_BATCH = 100

        /** `embeddings[i].values` in request order, each normalized to length 1. */
        fun vectorsOf(responseBody: String): List<FloatArray> =
            Json.parseToJsonElement(responseBody).jsonObject.getValue("embeddings").jsonArray.map { embedding ->
                val values = embedding.jsonObject.getValue("values").jsonArray
                Vectors.normalize(FloatArray(values.size) { values[it].jsonPrimitive.float })
            }
    }
}
