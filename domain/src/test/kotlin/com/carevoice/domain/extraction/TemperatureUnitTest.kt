package com.carevoice.domain.extraction

import com.carevoice.domain.assertRight
import com.carevoice.domain.model.BodyTemperature
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

@DisplayName("TemperatureUnit")
class TemperatureUnitTest {
    @Nested
    @DisplayName("Parsing")
    inner class Parsing {
        @ParameterizedTest(name = "should read \"{0}\" as Celsius")
        @FieldSource("com.carevoice.domain.TemperatureUnitValues#celsius")
        fun `should parse every spelling of Celsius`(raw: String) {
            assertThat(TemperatureUnit.parse(raw)).isEqualTo(TemperatureUnit.CELSIUS)
        }

        @ParameterizedTest(name = "should read \"{0}\" as Fahrenheit")
        @FieldSource("com.carevoice.domain.TemperatureUnitValues#fahrenheit")
        fun `should parse every spelling of Fahrenheit`(raw: String) {
            assertThat(TemperatureUnit.parse(raw)).isEqualTo(TemperatureUnit.FAHRENHEIT)
        }

        @ParameterizedTest(name = "should read \"{0}\" as unspecified")
        @FieldSource("com.carevoice.domain.TemperatureUnitValues#unspecified")
        fun `should parse a missing scale or a bare degrees as unspecified`(raw: String) {
            assertThat(TemperatureUnit.parse(raw)).isEqualTo(TemperatureUnit.UNSPECIFIED)
        }

        @Test
        fun `should parse no unit at all as unspecified`() {
            assertThat(TemperatureUnit.parse(null)).isEqualTo(TemperatureUnit.UNSPECIFIED)
        }

        @ParameterizedTest(name = "should not guess a scale for \"{0}\"")
        @FieldSource("com.carevoice.domain.TemperatureUnitValues#unsupported")
        fun `should return null for an unknown unit instead of guessing Celsius`(raw: String) {
            assertThat(TemperatureUnit.parse(raw)).isNull()
        }
    }

    @Nested
    @DisplayName("Inference")
    inner class Inference {
        @ParameterizedTest(name = "should read {0} as Celsius")
        @FieldSource("com.carevoice.domain.TemperatureUnitValues#celsiusMagnitudes")
        fun `should infer Celsius at or below the threshold`(value: Double) {
            assertThat(TemperatureUnit.inferFrom(value)).isEqualTo(TemperatureUnit.CELSIUS)
        }

        @ParameterizedTest(name = "should read {0} as Fahrenheit")
        @FieldSource("com.carevoice.domain.TemperatureUnitValues#fahrenheitMagnitudes")
        fun `should infer Fahrenheit above the threshold`(value: Double) {
            assertThat(TemperatureUnit.inferFrom(value)).isEqualTo(TemperatureUnit.FAHRENHEIT)
        }

        @Test
        fun `should put the threshold between the plausible Celsius and Fahrenheit ranges`() {
            assertThat(TemperatureUnit.FAHRENHEIT_THRESHOLD)
                .isGreaterThan(BodyTemperature.RANGE_CELSIUS.endInclusive)
                .isLessThan(86.0)
        }
    }

    @Nested
    @DisplayName("Measuring")
    inner class Measuring {
        @ParameterizedTest(name = "should store {0} °C as {1} °C")
        @FieldSource("com.carevoice.domain.TemperatureValues#validCelsius")
        fun `should measure a Celsius value as given`(value: Double, celsius: Double) {
            assertThat(TemperatureUnit.CELSIUS.measure(value)).isEqualTo(BodyTemperature.ofCelsius(celsius))
        }

        @ParameterizedTest(name = "should convert {0} °F to {1} °C")
        @FieldSource("com.carevoice.domain.TemperatureValues#validFahrenheit")
        fun `should convert a Fahrenheit value to Celsius`(value: Double, celsius: Double) {
            assertThat(TemperatureUnit.FAHRENHEIT.measure(value).assertRight().celsius).isEqualTo(celsius)
        }

        @ParameterizedTest(name = "should read unspecified {0} as {1} °C")
        @FieldSource("com.carevoice.domain.TemperatureValues#validCelsius")
        fun `should measure an unspecified Celsius magnitude as Celsius`(value: Double, celsius: Double) {
            assertThat(TemperatureUnit.UNSPECIFIED.measure(value).assertRight().celsius).isEqualTo(celsius)
        }

        @ParameterizedTest(name = "should read unspecified {0} as {1} °C")
        @FieldSource("com.carevoice.domain.TemperatureValues#validFahrenheit")
        fun `should measure an unspecified Fahrenheit magnitude as Fahrenheit`(value: Double, celsius: Double) {
            assertThat(TemperatureUnit.UNSPECIFIED.measure(value).assertRight().celsius).isEqualTo(celsius)
        }
    }
}
