package com.carevoice.domain.extraction

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.carevoice.domain.model.BloodPressure
import com.carevoice.domain.model.Confidence
import com.carevoice.domain.model.ExtractionSource
import com.carevoice.domain.model.Pulse
import com.carevoice.domain.model.ValidationError
import com.carevoice.domain.model.VitalSign

/*
 * Deterministic, regex-based extractors. They are fast, free, explainable and testable;
 * the AI extractor is only a fallback for what these cannot handle.
 *
 * Every pattern reads "<keyword> <short gap> <number>" on a transcript that already went through
 * TranscriptNormalizer (lowercase, "120 over 75" -> "120/75"). Three guards keep a number from
 * being attributed to the wrong vital sign, because a wrong value is worse than a missing one:
 *
 *  1. Keywords match whole words only, so "hr" does not match inside "three".
 *  2. The gap between keyword and number is short, contains no digits, never crosses a clause
 *     boundary (, ; . ! ?) and never skips over another vital keyword. In
 *     "temperature normal, pulse 80" the 80 therefore belongs to the pulse, not to the temperature.
 *  3. A number must stand on its own: "pulse 80.5" or "temp 37,2" are not read as 80 or 37.
 */

/**
 * How sure a rule is, by what the nurse actually said. Only [EXPLICIT] and [CELSIUS_ASSUMED] reach
 * [com.carevoice.domain.review.ReviewPolicy.CONFIDENCE_THRESHOLD]; everything inferred is reviewed by a human.
 * Named levels instead of scattered numbers, so the ordering is visible in one place and tests can refer to it.
 */
internal object RuleConfidence {
    /** Keyword and value explicit, e.g. "pulse 80" or "temperature 37.2 degrees celsius". */
    const val EXPLICIT = 0.95

    /** Temperature keyword without a unit, and Celsius is the only plausible reading, e.g. "temp 36.8". */
    const val CELSIUS_ASSUMED = 0.9

    /** No unit and the magnitude says Fahrenheit, e.g. "temp 101.3": the nurse never said Fahrenheit. */
    const val FAHRENHEIT_INFERRED = 0.7

    /** A bare "120/75" without a keyword: almost certainly blood pressure, but nobody said so. */
    const val NO_KEYWORD = 0.6
}

private const val BLOOD_PRESSURE_KEYWORDS = "blood pressure|bp"
private const val PULSE_KEYWORDS = "pulse|heart rate|hr"

private const val TEMPERATURE_KEYWORDS = "temperature|temp"

// A named scale may be introduced by "degrees" or "°" and be spelled out or abbreviated ("degrees fahrenheit",
// "° c", "°celsius"). Longest alternatives first: the unit group is optional, so with "degrees?" first the engine
// would accept "degrees" in "98.6 degrees fahrenheit" and never look further, and a missed explicit scale falls back
// to inference ("44 ° fahrenheit" would become 44 °C). TemperatureUnit.parse understands every alternative.
private const val TEMPERATURE_UNITS = """(?:degrees?\s+|°\s*)(?:fahrenheit|celsius|f|c)|fahrenheit|celsius|degrees?"""

/** Regex that matches one of [alternatives] only as a whole word, never inside a longer word (guard 1). */
private fun word(alternatives: String) = """(?<![a-z])(?:$alternatives)(?![a-z])"""

private val anyKeyword = word("$BLOOD_PRESSURE_KEYWORDS|$PULSE_KEYWORDS|$TEMPERATURE_KEYWORDS")

/** Up to [maxLength] characters between a keyword and its value (guard 2). Lazy, so the nearest number wins. */
private fun gap(maxLength: Int) = """(?:(?!$anyKeyword)[^\d,;.!?]){0,$maxLength}?"""

/** Guard 3: not glued to a preceding number ("1,120/75", "80/120/75"). */
private const val NUMBER_START = """(?<!\d|\d[.,/])"""

/** Guard 3: not continued by more digits, a decimal part or another slash. */
private const val NUMBER_END = """(?!\d|[.,/]\d)"""

/** Turns a validation result into a rule outcome: an error becomes a rejected candidate, a value an extracted vital. */
private fun <V : VitalSign> Either<ValidationError, V>.toOutcome(
    matchedText: String,
    confidence: Double,
): ExtractionOutcome = fold(
    ifLeft = { RejectedCandidate(matchedText, it).left() },
    ifRight = { ExtractedVital(it, Confidence(confidence), ExtractionSource.RULES, matchedText).right() },
)

/** "blood pressure 120/75", "bp: 135/85", or a bare "120/75" at lower confidence. */
class BloodPressureExtractor : VitalExtractor {
    // The keyword group is optional so a bare pair is still found, just with lower confidence.
    private val pattern =
        Regex("""(${word(BLOOD_PRESSURE_KEYWORDS)}${gap(20)})?$NUMBER_START(\d{2,3})/(\d{2,3})$NUMBER_END""")

    /** Finds every number pair; explicit confidence with a blood pressure keyword, lower confidence without. */
    override fun extract(normalized: String): List<ExtractionOutcome> = pattern
        .findAll(normalized)
        .map { match ->
            val hasKeyword = match.groupValues[1].isNotEmpty()
            BloodPressure
                .of(match.groupValues[2].toInt(), match.groupValues[3].toInt())
                .toOutcome(
                    matchedText = match.value.trim(),
                    confidence = if (hasKeyword) RuleConfidence.EXPLICIT else RuleConfidence.NO_KEYWORD,
                )
        }.toList()
}

/** "pulse 80", "heart rate of 72 bpm", "hr 110". */
class PulseExtractor : VitalExtractor {
    private val pattern = Regex("""${word(PULSE_KEYWORDS)}${gap(15)}(\d{2,3})$NUMBER_END""")

    /** Finds every pulse keyword followed by a two- or three-digit number, always at explicit confidence. */
    override fun extract(normalized: String): List<ExtractionOutcome> = pattern
        .findAll(normalized)
        .map { match ->
            Pulse.of(match.groupValues[1].toInt()).toOutcome(match.value.trim(), RuleConfidence.EXPLICIT)
        }
        .toList()
}

/**
 * "temperature 37.2 degrees", "temp 38.5 °c", "temperature 98.6 degrees fahrenheit", "temp 101.3".
 *
 * Also a transformation: Fahrenheit is converted to Celsius, the only unit the domain stores.
 * Without a named scale, the magnitude decides (see [TemperatureUnit.FAHRENHEIT_THRESHOLD]); a
 * Fahrenheit reading inferred that way is something the nurse did not say, so it gets a confidence
 * below the review threshold. Bare "degrees" names no scale either: "37 degrees" is Celsius,
 * "101 degrees" is Fahrenheit.
 */
class TemperatureExtractor : VitalExtractor {
    private val pattern =
        Regex(
            """${word(TEMPERATURE_KEYWORDS)}${gap(15)}(\d{2,3}(?:\.\d{1,2})?)$NUMBER_END""" +
                """(?:\s*($TEMPERATURE_UNITS)(?![a-z]))?""",
        )

    /** Finds every temperature, stores it in Celsius and sets the confidence by how clearly the unit was named. */
    override fun extract(normalized: String): List<ExtractionOutcome> = pattern
        .findAll(normalized)
        .map { match ->
            val value = match.groupValues[1].toDouble()
            val spokenUnit = match.groupValues[2]
            val unit =
                checkNotNull(TemperatureUnit.parse(spokenUnit)) { "pattern admitted unknown unit '$spokenUnit'" }
            val confidence =
                when {
                    unit != TemperatureUnit.UNSPECIFIED -> RuleConfidence.EXPLICIT

                    TemperatureUnit.inferFrom(value) == TemperatureUnit.FAHRENHEIT ->
                        RuleConfidence.FAHRENHEIT_INFERRED

                    // "37 degrees": the speaker said "degrees", which in a Celsius magnitude can only mean Celsius.
                    spokenUnit.isNotEmpty() -> RuleConfidence.EXPLICIT

                    else -> RuleConfidence.CELSIUS_ASSUMED
                }
            unit.measure(value).toOutcome(match.value.trim(), confidence)
        }.toList()
}
