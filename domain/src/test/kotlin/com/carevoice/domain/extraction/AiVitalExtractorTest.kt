package com.carevoice.domain.extraction

import arrow.core.left
import arrow.core.right
import com.carevoice.domain.assertRight
import com.carevoice.domain.model.Confidence
import com.carevoice.domain.model.ExtractionSource
import com.carevoice.domain.model.InconsistentValues
import com.carevoice.domain.model.ObservationStatus
import com.carevoice.domain.model.ValidationError
import com.carevoice.domain.model.VitalSign
import com.carevoice.domain.model.VitalType
import com.carevoice.domain.review.ReviewPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

@DisplayName("AI candidate validation")
class AiVitalExtractorTest {
    @Nested
    @DisplayName("Accepted candidates")
    inner class Accepted {
        @ParameterizedTest(name = "should accept {2}")
        @FieldSource("com.carevoice.domain.AiCandidateValues#valid")
        fun `should accept a plausible candidate with AI source and low confidence`(
            candidate: RawVitalCandidate,
            vital: VitalSign,
            description: String,
        ) {
            assertThat(candidate.validate())
                .isEqualTo(ExtractedVital(vital, Confidence(AI_CONFIDENCE), ExtractionSource.AI, description).right())
        }

        @ParameterizedTest(name = "should send {0} to review")
        @FieldSource("com.carevoice.domain.AiCandidateValues#validCandidates")
        fun `should always need review even when the value is plausible`(candidate: RawVitalCandidate) {
            assertThat(ReviewPolicy.statusFor(candidate.validate().assertRight()))
                .isEqualTo(ObservationStatus.NEEDS_REVIEW)
        }

        @Test
        fun `should give AI results a confidence below the review threshold`() {
            assertThat(AI_CONFIDENCE).isLessThan(ReviewPolicy.CONFIDENCE_THRESHOLD)
        }
    }

    @Nested
    @DisplayName("Rejected candidates")
    inner class Rejected {
        @ParameterizedTest(name = "should reject {1}")
        @FieldSource("com.carevoice.domain.AiCandidateValues#implausible")
        fun `should run the candidate through the same smart constructors as the rules`(
            candidate: RawVitalCandidate,
            description: String,
            error: ValidationError,
        ) {
            assertThat(candidate.validate()).isEqualTo(RejectedCandidate(description, error).left())
        }

        @ParameterizedTest(name = "should reject a pulse of {0}")
        @FieldSource("com.carevoice.domain.AiCandidateValues#notWholeNumbers")
        fun `should reject a pulse that is not a whole number instead of truncating it`(value: Double) {
            assertThat(RawVitalCandidate(VitalType.PULSE, value).validate()).isEqualTo(
                RejectedCandidate(
                    "AI: PULSE $value",
                    InconsistentValues("pulse must be a whole number, was $value"),
                ).left(),
            )
        }

        @ParameterizedTest(name = "should reject a systolic value of {0}")
        @FieldSource("com.carevoice.domain.AiCandidateValues#notWholeNumbers")
        fun `should reject a systolic value that is not a whole number`(value: Double) {
            assertThat(RawVitalCandidate(VitalType.BLOOD_PRESSURE, value, 75.0).validate()).isEqualTo(
                RejectedCandidate(
                    "AI: BLOOD_PRESSURE $value/75",
                    InconsistentValues("systolic must be a whole number, was $value"),
                ).left(),
            )
        }

        @ParameterizedTest(name = "should reject a diastolic value of {0}")
        @FieldSource("com.carevoice.domain.AiCandidateValues#notWholeNumbers")
        fun `should reject a diastolic value that is not a whole number`(value: Double) {
            assertThat(RawVitalCandidate(VitalType.BLOOD_PRESSURE, 120.0, value).validate()).isEqualTo(
                RejectedCandidate(
                    "AI: BLOOD_PRESSURE 120/$value",
                    InconsistentValues("diastolic must be a whole number, was $value"),
                ).left(),
            )
        }

        @ParameterizedTest(name = "should reject a pulse of {1}")
        @FieldSource("com.carevoice.domain.AiCandidateValues#beyondInt")
        fun `should reject a whole number beyond Int and report it as given instead of clamped`(
            value: Double,
            displayed: String,
        ) {
            assertThat(RawVitalCandidate(VitalType.PULSE, value).validate()).isEqualTo(
                RejectedCandidate(
                    "AI: PULSE $displayed",
                    InconsistentValues("pulse=$displayed is far outside any plausible value"),
                ).left(),
            )
        }

        @ParameterizedTest(name = "should reject unit \"{0}\"")
        @FieldSource("com.carevoice.domain.TemperatureUnitValues#unsupported")
        fun `should reject a temperature in an unsupported unit instead of assuming Celsius`(unit: String) {
            assertThat(RawVitalCandidate(VitalType.BODY_TEMPERATURE, 37.2, unit = unit).validate()).isEqualTo(
                RejectedCandidate(
                    "AI: BODY_TEMPERATURE 37.2 ${unit.trim()}",
                    InconsistentValues("temperature unit '$unit' is not supported"),
                ).left(),
            )
        }
    }
}
