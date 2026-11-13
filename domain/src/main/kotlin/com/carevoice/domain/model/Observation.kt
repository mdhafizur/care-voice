package com.carevoice.domain.model

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.time.Instant
import java.util.UUID

@JvmInline
value class ResidentId private constructor(val value: String) {
    /** Returns the raw id, not the default "ResidentId(value=...)" form. */
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("^[A-Za-z0-9-]{1,64}$")

        /** Accepts 1-64 letters, digits or '-'; anything else, including blank input, is a validation error. */
        fun of(raw: String): Either<ValidationError, ResidentId> = either {
            ensure(raw.isNotBlank()) { BlankInput("residentId") }
            ensure(FORMAT.matches(raw)) { InconsistentValues("residentId must be 1-64 letters, digits or '-'") }
            ResidentId(raw)
        }
    }
}

@JvmInline
value class ObservationId(val value: UUID) {
    /** Returns the plain UUID text, not the default "ObservationId(value=...)" form. */
    override fun toString(): String = value.toString()

    companion object {
        /** Makes a new id from a random UUID; TranscriptProcessor uses this by default. */
        fun random(): ObservationId = ObservationId(UUID.randomUUID())
    }
}

/** How sure the extractor is. 1.0 = explicit keyword and unit, lower = guessed from context. */
@JvmInline
value class Confidence(val value: Double) {
    init {
        // require(), not Either: confidence is produced by our own code, never by user input.
        // A bad value here is a programming bug, so failing fast is the right reaction.
        require(value in 0.0..1.0) { "confidence must be in [0, 1], was $value" }
    }
}

enum class ExtractionSource { RULES, AI }

enum class ObservationStatus {
    /** Extracted with high confidence by deterministic rules. */
    CONFIRMED,

    /** Low confidence or AI-extracted: a nurse must confirm before it counts. */
    NEEDS_REVIEW,
}

data class Observation(
    val id: ObservationId,
    val residentId: ResidentId,
    val vital: VitalSign,
    val recordedAt: Instant,
    val source: ExtractionSource,
    val confidence: Confidence,
    val status: ObservationStatus,
)
