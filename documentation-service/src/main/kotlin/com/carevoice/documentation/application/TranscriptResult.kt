package com.carevoice.documentation.application

import java.util.UUID

/** A value that sounded like a vital sign but failed validation; shown to the nurse instead of dropped silently. */
data class RejectedValue(val matchedText: String, val reason: String)

/**
 * The outcome of processing one transcript. Stored with the transcript on first processing and returned unchanged on
 * every idempotent replay and on `GET /v1/transcripts/{id}`, so all three answers are byte-for-byte the same body.
 */
data class TranscriptResult(
    val transcriptId: UUID,
    val residentId: String,
    val observations: List<ObservationRecord>,
    val rejected: List<RejectedValue>,
    val usedAiFallback: Boolean,
)
