package com.carevoice.alert.infrastructure.guidance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Shared bits of the Gemini REST API (`generativelanguage.googleapis.com/v1beta`). */
object GeminiApi {
    /** The key travels in this header, never as `?key=` in the URL (URLs end up in proxy and access logs). */
    const val API_KEY_HEADER = "x-goog-api-key"
    const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"

    /**
     * An alias that always points to Google's newest Flash model, so a new model needs no code change.
     * Model names: https://ai.google.dev/gemini-api/docs/models
     */
    const val DEFAULT_MODEL = "gemini-flash-latest"

    /** Google's text embedding model, used by [GeminiEmbedder]. */
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
