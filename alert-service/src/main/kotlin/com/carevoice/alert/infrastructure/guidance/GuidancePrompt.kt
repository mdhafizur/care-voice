package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.GuidanceStep
import com.carevoice.alert.domain.guidance.ScoredChunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The guidance prompt every LLM writer sends, so Claude and Gemini get the same rules and the same excerpts, and
 * their answers are read by the same [parse]. Only how a writer declines differs: Claude answers a sentinel word,
 * Gemini (whose output is held to a JSON schema) an empty step list.
 */
object GuidancePrompt {
    const val DECLINE = "NO_GUIDANCE"

    /** At most this many steps; also the `maxItems` of Gemini's response schema. */
    const val MAX_STEPS = 6

    private val RULES =
        """
        You support the ward lead of a care home who has just received a vital-sign alert.
        Write short, actionable steps for this alert using ONLY the protocol excerpts provided.
        Rules:
        - Every step cites the id of each excerpt it is based on, exactly as given (e.g. "hypotension#2").
        - Do not add facts, thresholds, medications, doses or diagnoses that are not in the excerpts.
        - At most $MAX_STEPS steps, each one sentence, in the order they should be done.
        """.trimIndent()

    val CLAUDE_SYSTEM: String =
        RULES + "\n" +
            """
            - If the excerpts do not cover this alert, answer exactly $DECLINE and nothing else.
            Answer with JSON only: {"steps": [{"text": "...", "citations": ["<excerpt id>"]}]}
            """.trimIndent()

    val GEMINI_SYSTEM: String =
        RULES + "\n" + """- If the excerpts do not cover this alert, return {"steps": []}."""

    /**
     * The alert text, then each excerpt in an <excerpt> tag with its id, protocol and section,
     * so the model can cite the excerpt by its id.
     */
    fun userPrompt(situation: String, sources: List<ScoredChunk>): String = buildString {
        append("Alert: ").append(situation).append("\n\nProtocol excerpts:\n")
        for (source in sources) {
            val chunk = source.chunk
            append("<excerpt id=\"").append(chunk.id).append("\" protocol=\"").append(chunk.documentTitle)
            append("\" section=\"").append(chunk.section).append("\">\n")
            append(chunk.text).append("\n</excerpt>\n")
        }
    }

    /**
     * Steps from the model's answer; null when it declined with [DECLINE]. Tolerates text around the JSON object (some
     * answers wrap it in a code fence). Throws on anything unparseable, which the use case treats as "fall back".
     */
    fun parse(answer: String): List<GuidanceStep>? {
        val trimmed = answer.trim()
        if (trimmed == DECLINE) return null
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "answer contains no JSON object" }
        val json = Json.parseToJsonElement(trimmed.substring(start, end + 1)).jsonObject
        val steps = json.getValue("steps").jsonArray
        return steps.map { element ->
            val step = element.jsonObject
            GuidanceStep(
                text = step.getValue("text").jsonPrimitive.content.trim(),
                citations = step["citations"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
        }
    }
}
