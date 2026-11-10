package com.carevoice.domain.extraction

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import com.carevoice.domain.model.BloodPressure
import com.carevoice.domain.model.Confidence
import com.carevoice.domain.model.ExtractionSource
import com.carevoice.domain.model.InconsistentValues
import com.carevoice.domain.model.Pulse
import com.carevoice.domain.model.ValidationError
import com.carevoice.domain.model.VitalSign
import com.carevoice.domain.model.VitalType
import kotlin.math.abs

/**
 * What an LLM returns: raw, untrusted numbers. Deliberately NOT a VitalSign,
 * because nothing from a model is allowed to skip domain validation.
 *
 * @property value systolic for blood pressure, beats per minute for pulse, degrees for temperature
 * @property secondaryValue diastolic for blood pressure, unused otherwise
 * @property unit temperature scale as the model wrote it ("C", "°F", "celsius", ...); null means not stated
 */
data class RawVitalCandidate(
    val type: VitalType,
    val value: Double,
    val secondaryValue: Double? = null,
    val unit: String? = null,
)

/**
 * Port (hexagonal architecture): the domain says WHAT it needs, the service module
 * decides HOW (Claude API, a local model, or a fake in tests).
 *
 * `suspend` is a language feature, so the domain still needs no coroutine library.
 *
 * Contract for adapters: an unavailable or failing model is not the nurse's problem. Adapters
 * translate timeouts and outages into an empty list (and record a metric), so the deterministic
 * rule results are never lost because a remote model failed.
 */
fun interface AiVitalExtractor {
    suspend fun extract(normalized: String): List<RawVitalCandidate>
}

/** Below [com.carevoice.domain.review.ReviewPolicy.CONFIDENCE_THRESHOLD] on purpose: AI results always need a human. */
internal const val AI_CONFIDENCE = 0.5

/**
 * Runs model output through exactly the same smart constructors as the rule-based path.
 *
 * On top of the range checks, model output is held to a stricter standard than speech: a pulse or a
 * blood pressure must be a whole number. A model answering "88.5" has made something up (nobody says
 * half a beat), and silently truncating it would store a value nobody said.
 */
internal fun RawVitalCandidate.validate(): ExtractionOutcome {
    val validated: Either<ValidationError, VitalSign> =
        when (type) {
            VitalType.BLOOD_PRESSURE -> validateBloodPressure()

            VitalType.PULSE -> wholeNumber("pulse", value).flatMap { Pulse.of(it) }

            VitalType.BODY_TEMPERATURE ->
                TemperatureUnit.parse(unit)?.measure(value)
                    ?: InconsistentValues("temperature unit '$unit' is not supported").left()
        }
    val description = describe()
    return validated.fold(
        ifLeft = { RejectedCandidate(description, it).left() },
        ifRight = { ExtractedVital(it, Confidence(AI_CONFIDENCE), ExtractionSource.AI, description).right() },
    )
}

private fun RawVitalCandidate.validateBloodPressure(): Either<ValidationError, VitalSign> = either {
    val diastolic =
        ensureNotNull(secondaryValue) { InconsistentValues("blood pressure needs a systolic and a diastolic value") }
    // bind(): the value of a Right, or stop the whole block with the Left (first error wins).
    val systolicMmHg = wholeNumber("systolic", value).bind()
    val diastolicMmHg = wholeNumber("diastolic", diastolic).bind()
    BloodPressure.of(systolicMmHg, diastolicMmHg).bind()
}

/**
 * Range checks happen in the smart constructors; this only refuses what cannot be converted to an Int
 * without changing the value: fractions, NaN, infinities and magnitudes beyond Int (which Kotlin's
 * toInt() would silently clamp to Int.MAX_VALUE, so the error would report a number nobody said).
 */
private fun wholeNumber(fieldName: String, raw: Double): Either<ValidationError, Int> = either {
    ensure(raw.isFinite() && raw == Math.rint(raw)) {
        InconsistentValues("$fieldName must be a whole number, was $raw")
    }
    ensure(raw in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
        InconsistentValues("$fieldName=${raw.display()} is far outside any plausible value")
    }
    raw.toInt()
}

/**
 * The text shown to a nurse for an AI result (there is no matched transcript text), e.g. "AI: BLOOD_PRESSURE 120/75".
 * Whole numbers are printed without ".0" so it reads like what a person would have said.
 */
private fun RawVitalCandidate.describe(): String {
    val values = listOfNotNull(value, secondaryValue).joinToString("/") { it.display() }
    return listOfNotNull("AI:", type.name, values, unit?.takeIf { it.isNotBlank() }?.trim()).joinToString(" ")
}

private fun Double.display(): String = if (isWhole()) toLong().toString() else toString()

private fun Double.isWhole(): Boolean = isFinite() && this == Math.rint(this) && abs(this) < MAX_EXACT_WHOLE

/** Beyond 2^53 a Double cannot represent every integer, and toLong() would clamp, so such values print as written. */
private const val MAX_EXACT_WHOLE = 9.007199254740992E15
