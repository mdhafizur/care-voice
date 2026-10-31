package com.carevoice.documentation.infrastructure.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Reads the answer text out of an Anthropic Messages API response; the text itself goes to [VitalAnswerParser]. */
internal object ClaudeResponseParser {
    // Strict parsing on purpose (the default): lenient mode would accept unquoted keys and other half-JSON.
    private val json: Json = Json.Default

    /** Text of all `text` content blocks of a Messages API response body, or null when the body is not one. */
    fun textOf(responseBody: String): String? = runCatching {
        json
            .parseToJsonElement(responseBody)
            .jsonObject["content"]
            ?.jsonArray
            ?.mapNotNull { block ->
                val obj = block as? JsonObject ?: return@mapNotNull null
                if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "text") return@mapNotNull null
                (obj["text"] as? JsonPrimitive)?.contentOrNull
            }?.joinToString("\n")
    }.getOrNull()
}
