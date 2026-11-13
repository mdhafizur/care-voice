package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.GuidanceGenerator
import com.carevoice.alert.application.GuidanceStep
import com.carevoice.alert.application.GuidanceWriter
import com.carevoice.alert.domain.guidance.ScoredChunk
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration of the Gemini adapters (env: `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_THINKING_LEVEL`).
 *
 * @property thinkingLevel `thinkingConfig.thinkingLevel`, or null to send none (Gemini 2.5 models reject it).
 */
data class GeminiSettings(
    val apiKey: String,
    val model: String = GeminiApi.DEFAULT_MODEL,
    val timeout: Duration = 20.seconds,
    val baseUrl: String = GeminiApi.DEFAULT_BASE_URL,
    val thinkingLevel: String? = GeminiApi.DEFAULT_THINKING_LEVEL,
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
    }

    /** Hides the API key, so the settings can be logged safely. */
    override fun toString(): String =
        "GeminiSettings(apiKey=***, model=$model, timeout=$timeout, baseUrl=$baseUrl, thinkingLevel=$thinkingLevel)"
}

/**
 * Generation step of the guidance RAG through Gemini (`GUIDANCE_AI_ENABLED=true`, `AI_PROVIDER=gemini`). Same rules
 * and excerpts as [ClaudeGuidanceWriter] ([GuidancePrompt]), and the same grounding check afterwards in the use case.
 *
 * Gemini-specific:
 * - **Structured output** ([responseSchema]): the answer must be `{"steps": [{"text", "citations"}]}`, at most
 *   [GuidancePrompt.MAX_STEPS] steps, and every citation must be one of the ids of the excerpts sent in this request
 *   (an `enum` built per request). A hallucinated citation id is then impossible to produce; the grounding check stays,
 *   because a valid id can still be cited for a step it does not support.
 * - **Declining** is an empty step list, since the schema does not allow a sentinel word.
 * - **Thinking** at the configured level (default `low`): the steps are rephrased protocol text, not a reasoning task.
 */
class GeminiGuidanceWriter(private val settings: GeminiSettings, private val httpClient: HttpClient) : GuidanceWriter {
    override val generator: GuidanceGenerator = GuidanceGenerator.GEMINI

    private val endpoint = GeminiApi.url(settings.baseUrl, settings.model, "generateContent")

    /**
     * Calls Gemini `generateContent` and turns the JSON answer into steps.
     * An empty step list means the model declined and gives null; a non-2xx status or no answer text throws.
     */
    override suspend fun write(situation: String, sources: List<ScoredChunk>): List<GuidanceStep>? {
        val response =
            httpClient.post(endpoint) {
                timeout { requestTimeoutMillis = settings.timeout.inWholeMilliseconds }
                header(GeminiApi.API_KEY_HEADER, settings.apiKey)
                setBody(TextContent(requestBody(situation, sources), ContentType.Application.Json))
            }
        val body = response.bodyAsText()
        logProviderResponse("gemini", settings.model, "write", response.status.value, body)
        check(response.status.isSuccess()) { "Gemini API failed with HTTP ${response.status.value}" }
        val text = checkNotNull(GeminiApi.textOf(body)) { "Gemini returned no answer text" }
        return GuidancePrompt.parse(text)?.takeIf { it.isNotEmpty() }
    }

    /**
     * JSON body: system prompt, user prompt, and a response schema that only allows the ids of [sources] as
     * citations. The thinking setting is sent only when one is configured.
     */
    fun requestBody(situation: String, sources: List<ScoredChunk>): String = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", GuidancePrompt.GEMINI_SYSTEM) } }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", GuidancePrompt.userPrompt(situation, sources)) } }
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseJsonSchema", responseSchema(sources.map { it.chunk.id }))
            put("maxOutputTokens", MAX_OUTPUT_TOKENS)
            settings.thinkingLevel?.let { level -> putJsonObject("thinkingConfig") { put("thinkingLevel", level) } }
        }
    }.toString()

    companion object {
        /** Thinking tokens count against this limit, so it is well above what six one-sentence steps need. */
        const val MAX_OUTPUT_TOKENS = 2048

        /** `{"steps": [{"text": string, "citations": [one of citationIds]}]}`, at most [GuidancePrompt.MAX_STEPS]. */
        fun responseSchema(citationIds: List<String>): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("steps") {
                    put("type", "array")
                    put("maxItems", GuidancePrompt.MAX_STEPS)
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("text") { put("type", "string") }
                            putJsonObject("citations") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "string")
                                    putJsonArray("enum") { citationIds.forEach { add(it) } }
                                }
                            }
                        }
                        putJsonArray("required") {
                            add("text")
                            add("citations")
                        }
                    }
                }
            }
            putJsonArray("required") { add("steps") }
        }
    }
}

/** Shared bits of the Gemini REST API (`generativelanguage.googleapis.com/v1beta`). */
object GeminiApi {
    /** The key travels in this header, never as `?key=` in the URL (URLs end up in proxy and access logs). */
    const val API_KEY_HEADER = "x-goog-api-key"
    const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
    const val DEFAULT_MODEL = "gemini-flash-latest"
    const val DEFAULT_EMBEDDING_MODEL = "gemini-embedding-001"

    /** The lowest level Gemini 3.8 Flash accepts (`minimal` is rejected there). */
    const val DEFAULT_THINKING_LEVEL = "low"

    /** `{base}/v1beta/models/{model}:{method}`; `models/x` and `x` are both accepted as the model name. */
    fun url(baseUrl: String, model: String, method: String): String =
        baseUrl.trimEnd('/') + "/v1beta/models/" + model.removePrefix("models/") + ":" + method

    /**
     * Text of the first candidate's parts without thought summaries (`"thought": true`); null when there is no
     * candidate (prompt blocked, `promptFeedback.blockReason`) or no text (cut off by `MAX_TOKENS`).
     */
    fun textOf(responseBody: String): String? = runCatching {
        val candidate = Json.parseToJsonElement(responseBody).jsonObject["candidates"]?.jsonArray?.firstOrNull()
        val parts = (candidate as? JsonObject)?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: return null
        parts
            .mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                if ((obj["thought"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
                (obj["text"] as? JsonPrimitive)?.contentOrNull
            }.joinToString("")
            .takeIf { it.isNotBlank() }
    }.getOrNull()
}
