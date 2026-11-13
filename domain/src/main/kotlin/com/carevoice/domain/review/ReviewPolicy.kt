package com.carevoice.domain.review

import com.carevoice.domain.extraction.ExtractedVital
import com.carevoice.domain.model.ExtractionSource
import com.carevoice.domain.model.ObservationStatus

/**
 * Business rule, kept in one place: a wrong vital sign in a care record is worse than a
 * missing one. So anything AI-extracted or uncertain must be confirmed by a nurse.
 */
object ReviewPolicy {
    const val CONFIDENCE_THRESHOLD = 0.8

    /** AI results always need review; rule results need review only below [CONFIDENCE_THRESHOLD]. */
    fun statusFor(extracted: ExtractedVital): ObservationStatus = when {
        extracted.source == ExtractionSource.AI -> ObservationStatus.NEEDS_REVIEW
        extracted.confidence.value < CONFIDENCE_THRESHOLD -> ObservationStatus.NEEDS_REVIEW
        else -> ObservationStatus.CONFIRMED
    }
}
