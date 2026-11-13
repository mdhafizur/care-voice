package com.carevoice.domain.extraction

import arrow.core.Either
import com.carevoice.domain.model.Confidence
import com.carevoice.domain.model.ExtractionSource
import com.carevoice.domain.model.ValidationError
import com.carevoice.domain.model.VitalSign

/** A vital sign found in the text, already validated. */
data class ExtractedVital(
    val vital: VitalSign,
    val confidence: Confidence,
    val source: ExtractionSource,
    val matchedText: String,
)

/** Something that looked like a vital sign but failed validation, e.g. "BP 12/10". */
data class RejectedCandidate(val matchedText: String, val error: ValidationError)

typealias ExtractionOutcome = Either<RejectedCandidate, ExtractedVital>

/**
 * Strategy pattern: one implementation per vital type. Adding SpO2 means adding one class
 * and registering it; no existing extractor changes (open/closed principle).
 *
 * `fun interface` makes it a SAM type, so a test can pass a lambda instead of a mock.
 */
fun interface VitalExtractor {
    /** @param normalized text already passed through [TranscriptNormalizer] */
    fun extract(normalized: String): List<ExtractionOutcome>
}

/**
 * Composite pattern: many extractors behind the same interface as a single one.
 * Callers do not know or care how many strategies run.
 */
class CompositeExtractor(private val extractors: List<VitalExtractor>) : VitalExtractor {
    /** Runs every extractor in list order and joins all their outcomes into one list. */
    override fun extract(normalized: String): List<ExtractionOutcome> = extractors.flatMap { it.extract(normalized) }

    companion object {
        /** The standard rule set: blood pressure, pulse and temperature extractors. */
        fun defaultRules(): CompositeExtractor = CompositeExtractor(
            listOf(
                BloodPressureExtractor(),
                PulseExtractor(),
                TemperatureExtractor(),
            ),
        )
    }
}
