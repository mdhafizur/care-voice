package com.carevoice.documentation.infrastructure.ai

/**
 * The extraction prompt every provider sends, so Claude and Gemini are asked exactly the same thing and their answers
 * are read by the same [VitalAnswerParser].
 */
object VitalExtractionPrompt {
    /**
     * Instructions only; the transcript goes into the user message inside `<transcript>` tags ([userMessage]), so
     * text a nurse dictated can never be mistaken for instructions. The output format is the exact shape
     * [VitalAnswerParser] reads; "only values that were said" because inventing a value is the worst failure in a
     * care record.
     */
    val SYSTEM: String =
        """
        You extract vital signs from English nursing documentation transcripts (speech-to-text output).
        Return ONLY a JSON array, with no prose and no Markdown. Each element is an object:
        {"type": "BLOOD_PRESSURE" | "PULSE" | "BODY_TEMPERATURE", "value": number, "secondaryValue": number | null, "unit": string | null}
        - BLOOD_PRESSURE: value = systolic, secondaryValue = diastolic, unit = null.
        - PULSE: value = beats per minute, secondaryValue = null, unit = null.
        - BODY_TEMPERATURE: value = the number as spoken, unit = "C" or "F" when the speaker named the scale, else null.
        Include only values that are explicitly stated in the transcript. Never estimate, convert, round or infer.
        If no vital sign is stated, return [].
        """.trimIndent()

    fun userMessage(normalized: String): String = "<transcript>\n$normalized\n</transcript>"
}
