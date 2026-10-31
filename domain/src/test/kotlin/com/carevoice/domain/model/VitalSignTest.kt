package com.carevoice.domain.model

import com.carevoice.domain.BloodPressureValues.PLAUSIBLE_DIASTOLIC
import com.carevoice.domain.BloodPressureValues.PLAUSIBLE_SYSTOLIC
import com.carevoice.domain.assertRight
import com.carevoice.domain.common.Either
import com.carevoice.domain.common.left
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

@DisplayName("VitalSign")
class VitalSignTest {
    @Nested
    @DisplayName("BloodPressure")
    inner class BloodPressureTest {
        @ParameterizedTest(name = "should accept {0}/{1}")
        @FieldSource("com.carevoice.domain.BloodPressureValues#valid")
        fun `should accept a plausible and consistent pair`(systolic: Int, diastolic: Int) {
            val bloodPressure = BloodPressure.of(systolic, diastolic).assertRight()

            assertThat(bloodPressure.systolic).isEqualTo(systolic)
            assertThat(bloodPressure.diastolic).isEqualTo(diastolic)
            assertThat(bloodPressure.type).isEqualTo(VitalType.BLOOD_PRESSURE)
        }

        @ParameterizedTest(name = "should reject systolic {0}")
        @FieldSource("com.carevoice.domain.BloodPressureValues#systolicOutOfRange")
        fun `should reject a systolic value outside the plausible range`(systolic: Int) {
            assertThat(BloodPressure.of(systolic, PLAUSIBLE_DIASTOLIC))
                .isEqualTo(OutOfRange("systolic", systolic, 50, 260).left())
        }

        @ParameterizedTest(name = "should reject diastolic {0}")
        @FieldSource("com.carevoice.domain.BloodPressureValues#diastolicOutOfRange")
        fun `should reject a diastolic value outside the plausible range`(diastolic: Int) {
            assertThat(BloodPressure.of(PLAUSIBLE_SYSTOLIC, diastolic))
                .isEqualTo(OutOfRange("diastolic", diastolic, 30, 160).left())
        }

        @ParameterizedTest(name = "should reject {0}/{1}")
        @FieldSource("com.carevoice.domain.BloodPressureValues#inconsistent")
        fun `should reject a diastolic value that is not below the systolic value`(systolic: Int, diastolic: Int) {
            assertThat(BloodPressure.of(systolic, diastolic))
                .isEqualTo(InconsistentValues("diastolic ($diastolic) must be lower than systolic ($systolic)").left())
        }

        @Test
        fun `should report the systolic error first when both values are out of range`() {
            assertThat(BloodPressure.of(0, 0)).isEqualTo(OutOfRange("systolic", 0, 50, 260).left())
        }

        /**
         * Deliberately exhaustive instead of sampled: every combination of the two plausible ranges
         * (about 27,000 pairs, a few milliseconds) is checked against the rule "accepted exactly when
         * diastolic < systolic", so no boundary can hide between hand-picked samples.
         */
        @Test
        fun `should accept exactly the pairs with diastolic below systolic, exhaustively over both ranges`() {
            val wronglyDecided =
                BloodPressure.SYSTOLIC_RANGE.flatMap { systolic ->
                    BloodPressure.DIASTOLIC_RANGE
                        .filter { diastolic ->
                            (BloodPressure.of(systolic, diastolic) is Either.Right) != (diastolic < systolic)
                        }
                        .map { diastolic -> "$systolic/$diastolic" }
                }

            assertThat(wronglyDecided).isEmpty()
        }

        @Test
        fun `should define the plausible ranges used in error messages`() {
            assertThat(BloodPressure.SYSTOLIC_RANGE).isEqualTo(50..260)
            assertThat(BloodPressure.DIASTOLIC_RANGE).isEqualTo(30..160)
        }

        @Test
        fun `should be equal to another blood pressure with the same values`() {
            assertThat(BloodPressure.of(120, 75)).isEqualTo(BloodPressure.of(120, 75))
        }

        @Test
        fun `should print the reading with its unit`() {
            assertThat(BloodPressure.of(120, 75).assertRight().toString()).isEqualTo("120/75 mmHg")
        }
    }

    @Nested
    @DisplayName("Pulse")
    inner class PulseTest {
        @ParameterizedTest(name = "should accept {0} bpm")
        @FieldSource("com.carevoice.domain.PulseValues#valid")
        fun `should accept a plausible pulse`(bpm: Int) {
            val pulse = Pulse.of(bpm).assertRight()

            assertThat(pulse.bpm).isEqualTo(bpm)
            assertThat(pulse.type).isEqualTo(VitalType.PULSE)
        }

        @ParameterizedTest(name = "should reject {0} bpm")
        @FieldSource("com.carevoice.domain.PulseValues#outOfRange")
        fun `should reject a pulse outside the plausible range`(bpm: Int) {
            assertThat(Pulse.of(bpm)).isEqualTo(OutOfRange("pulse", bpm, 20, 250).left())
        }

        @Test
        fun `should define the plausible range used in error messages`() {
            assertThat(Pulse.RANGE).isEqualTo(20..250)
        }

        @Test
        fun `should print the reading with its unit`() {
            assertThat(Pulse.of(80).assertRight().toString()).isEqualTo("80 bpm")
        }
    }

    @Nested
    @DisplayName("BodyTemperature")
    inner class BodyTemperatureTest {
        @ParameterizedTest(name = "should store {0} °C as {1} °C")
        @FieldSource("com.carevoice.domain.TemperatureValues#validCelsius")
        fun `should accept a plausible Celsius value rounded to one decimal`(celsius: Double, stored: Double) {
            val temperature = BodyTemperature.ofCelsius(celsius).assertRight()

            assertThat(temperature.celsius).isEqualTo(stored)
            assertThat(temperature.type).isEqualTo(VitalType.BODY_TEMPERATURE)
        }

        @ParameterizedTest(name = "should reject {0} °C reported as {1}")
        @FieldSource("com.carevoice.domain.TemperatureValues#celsiusOutOfRange")
        fun `should reject a Celsius value outside the plausible range after rounding`(
            celsius: Double,
            reported: Double,
        ) {
            assertThat(BodyTemperature.ofCelsius(celsius))
                .isEqualTo(OutOfRange("temperature", reported, 30.0, 44.0).left())
        }

        @ParameterizedTest(name = "should convert {0} °F to {1} °C")
        @FieldSource("com.carevoice.domain.TemperatureValues#validFahrenheit")
        fun `should convert a plausible Fahrenheit value to Celsius`(fahrenheit: Double, celsius: Double) {
            assertThat(BodyTemperature.ofFahrenheit(fahrenheit).assertRight().celsius).isEqualTo(celsius)
        }

        @ParameterizedTest(name = "should reject {0} °F reported as {1} °C")
        @FieldSource("com.carevoice.domain.TemperatureValues#fahrenheitOutOfRange")
        fun `should reject an implausible Fahrenheit value with the converted Celsius value in the error`(
            fahrenheit: Double,
            celsius: Double,
        ) {
            assertThat(BodyTemperature.ofFahrenheit(fahrenheit))
                .isEqualTo(OutOfRange("temperature", celsius, 30.0, 44.0).left())
        }

        @ParameterizedTest(name = "should reject {0} °C")
        @FieldSource("com.carevoice.domain.TemperatureValues#nonFinite")
        fun `should reject a Celsius value that is not a finite number`(celsius: Double) {
            assertThat(BodyTemperature.ofCelsius(celsius))
                .isEqualTo(InconsistentValues("temperature must be a finite number, was $celsius").left())
        }

        @ParameterizedTest(name = "should reject {0} °F")
        @FieldSource("com.carevoice.domain.TemperatureValues#nonFinite")
        fun `should reject a Fahrenheit value that is not a finite number`(fahrenheit: Double) {
            assertThat(BodyTemperature.ofFahrenheit(fahrenheit))
                .isEqualTo(InconsistentValues("temperature must be a finite number, was $fahrenheit").left())
        }

        @Test
        fun `should define the plausible range used in error messages`() {
            assertThat(BodyTemperature.RANGE_CELSIUS).isEqualTo(30.0..44.0)
        }

        @Test
        fun `should be equal to the same temperature given in the other unit`() {
            assertThat(BodyTemperature.ofFahrenheit(98.6)).isEqualTo(BodyTemperature.ofCelsius(37.0))
        }

        @Test
        fun `should print the reading in Celsius`() {
            assertThat(BodyTemperature.ofCelsius(37.2).assertRight().toString()).isEqualTo("37.2 °C")
        }
    }
}
