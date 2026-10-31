package com.carevoice.documentation.infrastructure.ai

import com.carevoice.domain.extraction.RawVitalCandidate
import com.carevoice.domain.model.VitalType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Turns the model's answer text into candidates, trusting nothing. Shared by every provider: Gemini's schema-enforced
 * JSON and Claude's instructed JSON go through the same checks.
 *
 * Model output is untrusted input: it may wrap the JSON in a Markdown code fence, add a sentence before it, return a
 * single object instead of an array, use strings for numbers, or invent fields and vital types. Each entry is parsed
 * on its own and a malformed entry is dropped, never the whole answer. Plausibility (ranges, systolic > diastolic) is
 * NOT checked here: the domain's smart constructors do that for AI and rule results alike.
 */
internal object VitalAnswerParser {
    // Strict parsing on purpose (the default): lenient mode would accept unquoted keys and other half-JSON.
    private val json: Json = Json.Default

    /**
     * Candidates found in the model's [text]. Malformed entries are skipped, so a readable array whose entries are all
     * unusable gives an empty list; null means the text contained no readable JSON at all (the model misbehaved,
     * which the caller reports as an error rather than as "nothing found").
     */
    fun candidates(text: String): List<RawVitalCandidate>? = extractArray(text)?.mapNotNull(::toCandidate)

    /**
     * Finds the JSON array in [text]: strips a code fence if present, then takes the outermost `[` ... `]`. A top-level
     * object is accepted as a one-element array, because models occasionally return a single finding that way.
     */
    private fun extractArray(text: String): JsonArray? {
        val unfenced = FENCE.find(text)?.groupValues?.get(1) ?: text
        val trimmed = unfenced.trim()
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        val element: JsonElement =
            when {
                start >= 0 && end > start -> parseOrNull(trimmed.substring(start, end + 1))
                trimmed.startsWith("{") -> parseOrNull(trimmed)
                else -> null
            } ?: return null
        return when (element) {
            is JsonArray -> element
            is JsonObject -> JsonArray(listOf(element))
            else -> null
        }
    }

    private fun parseOrNull(candidate: String): JsonElement? = runCatching {
        json.parseToJsonElement(candidate)
    }.getOrNull()

    private fun toCandidate(element: JsonElement): RawVitalCandidate? {
        val obj = element as? JsonObject ?: return null
        val type =
            obj.string("type")?.uppercase()?.let { name -> VitalType.entries.firstOrNull { it.name == name } }
                ?: return null
        val value = obj.number("value")?.takeIf { it.isFinite() } ?: return null
        val secondary = obj.number("secondaryValue")?.takeIf { it.isFinite() }
        if (type == VitalType.BLOOD_PRESSURE && secondary == null) return null
        return RawVitalCandidate(
            type = type,
            value = value,
            secondaryValue = if (type == VitalType.BLOOD_PRESSURE) secondary else null,
            unit = obj.string("unit")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_UNIT_LENGTH },
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Accepts `120` and `"120"`, because models are inconsistent about quoting numbers. */
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.let { primitive ->
        if (primitive.isString) primitive.content.trim().toDoubleOrNull() else primitive.doubleOrNull
    }

    private const val MAX_UNIT_LENGTH = 16

    /** ```json ... ``` or ``` ... ```, the content in group 1. */
    private val FENCE = Regex("```[a-zA-Z]*\\s*([\\s\\S]*?)```")
}
