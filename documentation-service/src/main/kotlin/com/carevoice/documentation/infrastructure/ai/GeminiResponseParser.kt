package com.carevoice.documentation.infrastructure.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

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
                obj.primitiveText("text")
            }.joinToString("")
            .takeIf { it.isNotBlank() }
    }.getOrNull()
}
