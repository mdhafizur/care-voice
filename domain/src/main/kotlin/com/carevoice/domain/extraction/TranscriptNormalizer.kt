package com.carevoice.domain.extraction

/**
 * Transformation step: turns the many ways people *say* a value into one canonical form,
 * so the extractors only need to understand one format.
 *
 *   "BP 120 over 75"                 -> "bp 120/75"
 *   "Blood pressure 120 slash 75"    -> "blood pressure 120/75"
 *   "bp 120 / 75"                    -> "bp 120/75"
 *   "Temperature 37 point 2 degrees" -> "temperature 37.2 degrees"
 *
 * Every rewrite is anchored between two digits, so ordinary words stay untouched:
 * "walked over to the window" keeps its "over", "at this point 2 nurses" keeps its "point".
 *
 * A decimal comma ("37,2") is deliberately NOT rewritten. The product targets English speech
 * recognition, which writes a decimal point; guessing that a comma is a decimal separator could turn
 * a list ("pulse 80,90") into a wrong value, and a wrong value is worse than a missing one.
 */
object TranscriptNormalizer {
    private val spokenSlash = Regex("""(?<=\d)\s+(?:over|slash)\s+(?=\d)""")
    private val spokenDecimalPoint = Regex("""(?<=\d)\s+point\s+(?=\d)""")
    private val spacedSlash = Regex("""(?<=\d)\s*/\s*(?=\d)""")
    private val whitespace = Regex("""\s+""")

    /** Lowercases the text, writes spoken "over"/"slash" as "/" and "point" as ".", and squeezes spaces to one. */
    fun normalize(raw: String): String = raw
        // Kotlin's lowercase() uses Locale.ROOT, so a server with a Turkish default locale still turns
        // "CELSIUS" into "celsius" (and not into "celsıus" with a dotless i that no pattern would match).
        .lowercase()
        .replace(spokenDecimalPoint, ".")
        .replace(spokenSlash, "/")
        .replace(spacedSlash, "/")
        .replace(whitespace, " ")
        .trim()
}
