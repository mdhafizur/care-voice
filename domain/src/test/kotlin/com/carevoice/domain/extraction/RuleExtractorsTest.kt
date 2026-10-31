package com.carevoice.domain.extraction

import com.carevoice.domain.assertRight
import com.carevoice.domain.common.left
import com.carevoice.domain.common.right
import com.carevoice.domain.model.BlankInput
import com.carevoice.domain.model.BloodPressure
import com.carevoice.domain.model.BodyTemperature
import com.carevoice.domain.model.Confidence
import com.carevoice.domain.model.ExtractionSource
import com.carevoice.domain.model.OutOfRange
import com.carevoice.domain.model.Pulse
import com.carevoice.domain.model.ValidationError
import com.carevoice.domain.model.VitalSign
import com.carevoice.domain.review.ReviewPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

/** Inputs are already normalized (lowercase, "120/75"), exactly what the extractors receive in production. */
@DisplayName("Rule extractors")
class RuleExtractorsTest {
    private fun extracted(vital: VitalSign, confidence: Double, matchedText: String): ExtractionOutcome =
        ExtractedVital(vital, Confidence(confidence), ExtractionSource.RULES, matchedText).right()

    private fun celsius(value: Double): VitalSign = BodyTemperature.ofCelsius(value).assertRight()

    private fun rejected(matchedText: String, error: ValidationError): ExtractionOutcome =
        RejectedCandidate(matchedText, error).left()

    @Nested
    @DisplayName("Confidence levels")
    inner class ConfidenceLevels {
        @Test
        fun `should keep only explicit and Celsius-assumed readings at or above the review threshold`() {
            assertThat(listOf(RuleConfidence.EXPLICIT, RuleConfidence.CELSIUS_ASSUMED))
                .allMatch { it >= ReviewPolicy.CONFIDENCE_THRESHOLD }
            assertThat(listOf(RuleConfidence.FAHRENHEIT_INFERRED, RuleConfidence.NO_KEYWORD))
                .allMatch { it < ReviewPolicy.CONFIDENCE_THRESHOLD }
        }

        @Test
        fun `should rank the levels by how much the nurse actually said`() {
            assertThat(
                listOf(
                    RuleConfidence.EXPLICIT,
                    RuleConfidence.CELSIUS_ASSUMED,
                    RuleConfidence.FAHRENHEIT_INFERRED,
                    RuleConfidence.NO_KEYWORD,
                ),
            ).isSortedAccordingTo(reverseOrder())
        }
    }

    @Nested
    @DisplayName("BloodPressureExtractor")
    inner class BloodPressureExtractorTest {
        private val extractor = BloodPressureExtractor()
        private val expected = BloodPressure.of(120, 75).assertRight()

        @ParameterizedTest(name = "should read \"{0}\" with explicit confidence")
        @FieldSource("com.carevoice.domain.ExtractorValues#bloodPressureWithKeyword")
        fun `should extract a reading after a keyword with explicit confidence`(text: String) {
            assertThat(extractor.extract(text)).containsExactly(extracted(expected, RuleConfidence.EXPLICIT, text))
        }

        @ParameterizedTest(name = "should read \"{0}\" as a bare pair")
        @FieldSource("com.carevoice.domain.ExtractorValues#bloodPressureWithoutKeyword")
        fun `should extract a bare pair with low confidence when no keyword belongs to it`(text: String) {
            assertThat(extractor.extract(text))
                .containsExactly(extracted(expected, RuleConfidence.NO_KEYWORD, "120/75"))
        }

        @ParameterizedTest(name = "should ignore \"{0}\"")
        @FieldSource("com.carevoice.domain.ExtractorValues#bloodPressureNotExtracted")
        fun `should not extract a pair glued to other digits or with the wrong number of digits`(text: String) {
            assertThat(extractor.extract(text)).isEmpty()
        }

        @ParameterizedTest(name = "should reject \"{0}\"")
        @FieldSource("com.carevoice.domain.ExtractorValues#bloodPressureRejected")
        fun `should return an implausible reading as a rejected candidate`(text: String, error: ValidationError) {
            assertThat(extractor.extract(text)).containsExactly(rejected(text, error))
        }

        @Test
        fun `should extract every reading in order when several are spoken`() {
            assertThat(extractor.extract("bp 120/75, later bp 135/85")).containsExactly(
                extracted(expected, RuleConfidence.EXPLICIT, "bp 120/75"),
                extracted(BloodPressure.of(135, 85).assertRight(), RuleConfidence.EXPLICIT, "bp 135/85"),
            )
        }
    }

    @Nested
    @DisplayName("PulseExtractor")
    inner class PulseExtractorTest {
        private val extractor = PulseExtractor()
        private val expected = Pulse.of(80).assertRight()

        @ParameterizedTest(name = "should read \"{0}\"")
        @FieldSource("com.carevoice.domain.ExtractorValues#pulseWithKeyword")
        fun `should extract a pulse after any of its keywords with explicit confidence`(text: String) {
            assertThat(extractor.extract(text)).containsExactly(extracted(expected, RuleConfidence.EXPLICIT, text))
        }

        @ParameterizedTest(name = "should ignore \"{0}\"")
        @FieldSource("com.carevoice.domain.ExtractorValues#pulseNotExtracted")
        fun `should not attribute a number to the pulse when it does not belong to it`(text: String) {
            assertThat(extractor.extract(text)).isEmpty()
        }

        @ParameterizedTest(name = "should reject \"{0}\"")
        @FieldSource("com.carevoice.domain.ExtractorValues#pulseRejected")
        fun `should return an implausible pulse as a rejected candidate`(text: String, error: ValidationError) {
            assertThat(extractor.extract(text)).containsExactly(rejected(text, error))
        }

        @Test
        fun `should take the number after the pulse keyword, not the one after an earlier vital`() {
            assertThat(extractor.extract("temperature normal, pulse 80"))
                .containsExactly(extracted(expected, RuleConfidence.EXPLICIT, "pulse 80"))
        }
    }

    @Nested
    @DisplayName("TemperatureExtractor")
    inner class TemperatureExtractorTest {
        private val extractor = TemperatureExtractor()

        @ParameterizedTest(name = "should read \"{0}\" as {1} °C")
        @FieldSource("com.carevoice.domain.ExtractorValues#temperatureExplicit")
        fun `should extract a temperature with a named scale with explicit confidence`(text: String, celsius: Double) {
            assertThat(extractor.extract(text))
                .containsExactly(extracted(celsius(celsius), RuleConfidence.EXPLICIT, text))
        }

        @ParameterizedTest(name = "should read \"{0}\" as {1} °C")
        @FieldSource("com.carevoice.domain.ExtractorValues#temperatureCelsiusAssumed")
        fun `should assume Celsius for a temperature without a unit in the Celsius range`(
            text: String,
            celsius: Double,
        ) {
            assertThat(extractor.extract(text))
                .containsExactly(extracted(celsius(celsius), RuleConfidence.CELSIUS_ASSUMED, text))
        }

        @ParameterizedTest(name = "should convert \"{0}\" to {1} °C")
        @FieldSource("com.carevoice.domain.ExtractorValues#temperatureFahrenheitInferred")
        fun `should convert an unnamed Fahrenheit magnitude with confidence below the review threshold`(
            text: String,
            celsius: Double,
        ) {
            assertThat(extractor.extract(text)).containsExactly(
                extracted(celsius(celsius), RuleConfidence.FAHRENHEIT_INFERRED, text),
            )
        }

        @ParameterizedTest(name = "should ignore \"{0}\"")
        @FieldSource("com.carevoice.domain.ExtractorValues#temperatureNotExtracted")
        fun `should not extract a temperature that is not a standalone number after its keyword`(text: String) {
            assertThat(extractor.extract(text)).isEmpty()
        }

        @ParameterizedTest(name = "should reject \"{0}\"")
        @FieldSource("com.carevoice.domain.ExtractorValues#temperatureRejected")
        fun `should return an implausible temperature as a rejected candidate`(text: String, error: ValidationError) {
            assertThat(extractor.extract(text)).containsExactly(rejected(text, error))
        }
    }

    @Nested
    @DisplayName("CompositeExtractor")
    inner class CompositeExtractorTest {
        @Test
        fun `should run every default rule and return their results in registration order`() {
            val text = "temperature 37.2 degrees, pulse 80, blood pressure 120/75"

            assertThat(CompositeExtractor.defaultRules().extract(text)).containsExactly(
                extracted(BloodPressure.of(120, 75).assertRight(), RuleConfidence.EXPLICIT, "blood pressure 120/75"),
                extracted(Pulse.of(80).assertRight(), RuleConfidence.EXPLICIT, "pulse 80"),
                extracted(celsius(37.2), RuleConfidence.EXPLICIT, "temperature 37.2 degrees"),
            )
        }

        @Test
        fun `should keep rejected candidates next to extracted values`() {
            assertThat(CompositeExtractor.defaultRules().extract("bp 12/10, pulse 80")).containsExactly(
                rejected("bp 12/10", OutOfRange("systolic", 12, 50, 260)),
                extracted(Pulse.of(80).assertRight(), RuleConfidence.EXPLICIT, "pulse 80"),
            )
        }

        @Test
        fun `should combine the results of any extractors in the order given`() {
            val first = VitalExtractor { listOf(extracted(Pulse.of(70).assertRight(), 0.95, "first")) }
            val second = VitalExtractor { listOf(rejected("second", BlankInput("x"))) }

            assertThat(CompositeExtractor(listOf(first, second)).extract("anything")).containsExactly(
                extracted(Pulse.of(70).assertRight(), 0.95, "first"),
                rejected("second", BlankInput("x")),
            )
        }

        @Test
        fun `should find nothing when no extractor is registered`() {
            assertThat(CompositeExtractor(emptyList()).extract("pulse 80")).isEmpty()
        }

        @Test
        fun `should find nothing in a text without vital signs`() {
            assertThat(CompositeExtractor.defaultRules().extract("resident slept well, no complaints")).isEmpty()
        }
    }
}
