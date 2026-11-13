package com.carevoice.domain.application

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.carevoice.domain.extraction.AiVitalExtractor
import com.carevoice.domain.extraction.CompositeExtractor
import com.carevoice.domain.extraction.ExtractedVital
import com.carevoice.domain.extraction.ExtractionOutcome
import com.carevoice.domain.extraction.RejectedCandidate
import com.carevoice.domain.extraction.TranscriptNormalizer
import com.carevoice.domain.extraction.VitalExtractor
import com.carevoice.domain.extraction.validate
import com.carevoice.domain.model.BlankInput
import com.carevoice.domain.model.Observation
import com.carevoice.domain.model.ObservationId
import com.carevoice.domain.model.ResidentId
import com.carevoice.domain.model.TooLong
import com.carevoice.domain.model.ValidationError
import com.carevoice.domain.review.ReviewPolicy
import java.time.Clock

data class ProcessingResult(
    val observations: List<Observation>,
    val rejected: List<RejectedCandidate>,
    val usedAiFallback: Boolean,
)

/**
 * Use case: transcript in, observations out.
 *
 *   "Mrs. Smith, blood pressure 120 over 75, pulse 80, temperature 37.2 degrees"
 *   -> BloodPressure(120/75), Pulse(80), BodyTemperature(37.2 °C), all CONFIRMED
 *
 * Pipeline (each step replaceable and tested on its own):
 *   validate input -> normalize -> rule extractors -> [AI fallback] -> review policy -> observations
 *
 * Chain of responsibility: the cheap, deterministic rules go first; the AI is only asked
 * when the rules found nothing at all. That keeps latency and cost down for the common case,
 * and it means a model can never overrule or duplicate a value the rules already understood.
 *
 * Values that looked like a vital sign but failed validation are returned in
 * [ProcessingResult.rejected] instead of being dropped silently, so the nurse sees that
 * "bp 12/10" was heard but not stored. Rejected candidates alone do not count as "found
 * something": a transcript with only implausible values still gets the AI fallback.
 *
 * Dependencies come in through the constructor (no DI framework needed in the domain).
 * Clock and id generation are injected so tests are deterministic.
 */
class TranscriptProcessor(
    private val rules: VitalExtractor = CompositeExtractor.defaultRules(),
    private val aiFallback: AiVitalExtractor? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> ObservationId = ObservationId::random,
) {
    /**
     * Turns one transcript into observations for [residentId], all with the same timestamp.
     * A blank or too long transcript gives a Left (the error side of Either) instead of a result.
     * The AI fallback runs only when the rules found no valid value.
     */
    suspend fun process(residentId: ResidentId, transcript: String): Either<ValidationError, ProcessingResult> =
        either {
            ensure(transcript.isNotBlank()) { BlankInput("transcript") }
            ensure(transcript.length <= MAX_TRANSCRIPT_LENGTH) { TooLong("transcript", MAX_TRANSCRIPT_LENGTH) }

            val normalized = TranscriptNormalizer.normalize(transcript)
            val ruleOutcomes = rules.extract(normalized)

            val ai = aiFallback?.takeIf { ruleOutcomes.none { it is Either.Right } }
            val outcomes = if (ai == null) ruleOutcomes else ruleOutcomes + ai.extract(normalized).map { it.validate() }

            // One timestamp for the whole transcript: the values were spoken together, so they were observed together.
            val now = clock.instant()
            val extracted = outcomes.filterIsInstance<Either.Right<ExtractedVital>>().map { it.value }
            val rejected = outcomes.filterIsInstance<Either.Left<RejectedCandidate>>().map { it.value }

            val observations =
                extracted.map { vital ->
                    Observation(
                        id = newId(),
                        residentId = residentId,
                        vital = vital.vital,
                        recordedAt = now,
                        source = vital.source,
                        confidence = vital.confidence,
                        status = ReviewPolicy.statusFor(vital),
                    )
                }
            ProcessingResult(observations, rejected, usedAiFallback = ai != null)
        }

    companion object {
        /** Matches the API limit (422 above it); a minute of dictation is far below, so longer input is not a note. */
        const val MAX_TRANSCRIPT_LENGTH = 2_000
    }
}
