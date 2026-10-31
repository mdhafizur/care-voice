package com.carevoice.domain.review

import com.carevoice.domain.assertRight
import com.carevoice.domain.extraction.ExtractedVital
import com.carevoice.domain.model.Confidence
import com.carevoice.domain.model.ExtractionSource
import com.carevoice.domain.model.ObservationStatus
import com.carevoice.domain.model.Pulse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

@DisplayName("ReviewPolicy")
class ReviewPolicyTest {
    private fun extracted(source: ExtractionSource, confidence: Double) =
        ExtractedVital(Pulse.of(80).assertRight(), Confidence(confidence), source, "pulse 80")

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @ParameterizedTest(name = "should confirm a rule result with confidence {0}")
        @FieldSource("com.carevoice.domain.ConfidenceValues#confirmedForRules")
        fun `should confirm a rule result at or above the threshold`(confidence: Double) {
            assertThat(ReviewPolicy.statusFor(extracted(ExtractionSource.RULES, confidence)))
                .isEqualTo(ObservationStatus.CONFIRMED)
        }

        @ParameterizedTest(name = "should review a rule result with confidence {0}")
        @FieldSource("com.carevoice.domain.ConfidenceValues#reviewedForRules")
        fun `should send a rule result below the threshold to review`(confidence: Double) {
            assertThat(ReviewPolicy.statusFor(extracted(ExtractionSource.RULES, confidence)))
                .isEqualTo(ObservationStatus.NEEDS_REVIEW)
        }

        @ParameterizedTest(name = "should review an AI result with confidence {0}")
        @FieldSource("com.carevoice.domain.ConfidenceValues#any")
        fun `should send every AI result to review regardless of its confidence`(confidence: Double) {
            assertThat(ReviewPolicy.statusFor(extracted(ExtractionSource.AI, confidence)))
                .isEqualTo(ObservationStatus.NEEDS_REVIEW)
        }

        @Test
        fun `should require a confidence of at least 80 percent for automatic confirmation`() {
            assertThat(ReviewPolicy.CONFIDENCE_THRESHOLD).isEqualTo(0.8)
        }
    }
}
