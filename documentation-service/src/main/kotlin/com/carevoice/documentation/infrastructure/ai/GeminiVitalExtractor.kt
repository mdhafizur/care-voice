package com.carevoice.documentation.infrastructure.ai

import com.carevoice.documentation.resilience.CircuitBreakerSettings
import com.carevoice.documentation.resilience.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Vital-sign extraction through the Gemini API (`POST /v1beta/models/{model}:generateContent`), selected with
 * `AI_PROVIDER=gemini`. Resilience, metrics and the failure policy live in [HttpAiVitalExtractor]; this class is the
 * wire format only. The API key is sent in `x-goog-api-key`, never in the URL (URLs end up in proxy and access logs).
 *
 * Differences from Claude, all in the request:
 * - **Structured output**: `responseMimeType: application/json` plus a JSON Schema ([RESPONSE_SCHEMA]) for exactly the
 *   array [VitalAnswerParser] reads. The model then cannot answer in prose; the parser still checks every entry,
 *   because a schema constrains the shape, not the truth.
 * - **Thinking**: Gemini 3 models think before answering by default (a 10-token answer can cost ~300 thinking
 *   tokens). Reading numbers out of a sentence needs no reasoning, so [AiSettings.thinkingLevel] (default `low`,
 *   the lowest Gemini 3.8 Flash accepts) keeps latency and cost down. Thinking tokens count against
 *   `maxOutputTokens`, which is therefore larger than Claude's `max_tokens`.
 * - **No temperature**: Google recommends the default temperature for Gemini 3 models; determinism comes from the
 *   schema and the parser.
 */
class GeminiVitalExtractor(
    settings: AiSettings,
    httpClient: HttpClient,
    meterRegistry: MeterRegistry,
    circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(),
    retryPolicy: RetryPolicy = HttpAiVitalExtractor.DEFAULT_RETRY_POLICY,
) : HttpAiVitalExtractor(
    settings,
    httpClient,
    meterRegistry,
    CIRCUIT_BREAKER_NAME,
    circuitBreakerSettings,
    retryPolicy,
) {
    override val endpoint: String = generateContentUrl(settings.baseUrl, settings.model)

    /** Sends the API key as a header, not in the URL. */
    override fun HttpRequestBuilder.authenticate() {
        header(API_KEY_HEADER, settings.apiKey)
    }

    /**
     * Builds a `generateContent` request: the shared prompt, JSON-only output with [RESPONSE_SCHEMA],
     * an output token limit and, when set, the thinking level.
     */
    override fun requestBody(normalized: String): String = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", VitalExtractionPrompt.SYSTEM) } }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", VitalExtractionPrompt.userMessage(normalized)) } }
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseJsonSchema", RESPONSE_SCHEMA)
            put("maxOutputTokens", MAX_OUTPUT_TOKENS)
            settings.thinkingLevel?.let { level -> putJsonObject("thinkingConfig") { put("thinkingLevel", level) } }
        }
    }.toString()

    /** Reads the first candidate's answer text, skipping thought summaries. */
    override fun answerText(responseBody: String): String? = GeminiResponseParser.textOf(responseBody)

    companion object {
        const val API_KEY_HEADER = "x-goog-api-key"
        const val CIRCUIT_BREAKER_NAME = "gemini"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_MODEL = "gemini-flash-latest"
        const val DEFAULT_THINKING_LEVEL = "low"

        /** Room for low-level thinking plus a JSON array of a few vitals. */
        private const val MAX_OUTPUT_TOKENS = 1024

        /** `models/gemini-x` and `gemini-x` are both accepted as the model name. */
        fun generateContentUrl(baseUrl: String, model: String): String =
            baseUrl.trimEnd('/') + "/v1beta/models/" + model.removePrefix("models/") + ":generateContent"

        /** JSON Schema of the answer: the array [VitalExtractionPrompt.SYSTEM] describes, nulls spelled as types. */
        val RESPONSE_SCHEMA: JsonElement =
            Json.parseToJsonElement(
                """
                {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "type": {"type": "string", "enum": ["BLOOD_PRESSURE", "PULSE", "BODY_TEMPERATURE"]},
                      "value": {"type": "number"},
                      "secondaryValue": {"type": ["number", "null"]},
                      "unit": {"type": ["string", "null"]}
                    },
                    "required": ["type", "value", "secondaryValue", "unit"]
                  }
                }
                """.trimIndent(),
            )
    }
}

/** Reads the answer text out of a `generateContent` response; the text itself goes to [VitalAnswerParser]. */
internal object GeminiResponseParser {
    private val json: Json = Json.Default

    /**
     * Text of the first candidate's parts, without thought summaries (`"thought": true`). Null when the body is not a
     * `generateContent` response or has no candidate, e.g. when the prompt was blocked (`promptFeedback.blockReason`)
     * or the answer was cut off before any text.
     */
    fun textOf(responseBody: String): String? = runCatching {
        val candidate = json.parseToJsonElement(responseBody).jsonObject["candidates"]?.jsonArray?.firstOrNull()
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
