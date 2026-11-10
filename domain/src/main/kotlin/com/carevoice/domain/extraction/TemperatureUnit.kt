package com.carevoice.domain.extraction

import arrow.core.Either
import com.carevoice.domain.model.BodyTemperature
import com.carevoice.domain.model.ValidationError

/**
 * The temperature scale a speaker or a model named. Shared by the rule extractor and the AI validation,
 * so "98.6", "98.6 °F" and an AI answer with unit "F" are interpreted by exactly one set of rules.
 */
internal enum class TemperatureUnit {
    CELSIUS,
    FAHRENHEIT,

    /** No scale named: nothing at all, or just "degrees". The magnitude decides, see [inferFrom]. */
    UNSPECIFIED,
    ;

    fun measure(value: Double): Either<ValidationError, BodyTemperature> = when (this) {
        CELSIUS -> BodyTemperature.ofCelsius(value)
        FAHRENHEIT -> BodyTemperature.ofFahrenheit(value)
        UNSPECIFIED -> inferFrom(value).measure(value)
    }

    companion object {
        /**
         * Plausible body temperatures are 30-44 °C and 86-111 °F, so the two ranges cannot overlap.
         * Any value above 50 can only be Fahrenheit; anything below is read as Celsius (and a value
         * between 44 and 50 is then rejected as implausible instead of being guessed).
         */
        const val FAHRENHEIT_THRESHOLD = 50.0

        private val degreesWord = Regex("""degrees?""")

        /** Scale for a value whose unit was not named. */
        fun inferFrom(value: Double): TemperatureUnit = if (value > FAHRENHEIT_THRESHOLD) FAHRENHEIT else CELSIUS

        /**
         * Parses what a speaker or a model wrote as the unit: "°C", "celsius", "degrees Fahrenheit", "F",
         * "degrees", blank or null. Returns null for anything else (e.g. "kelvin"), so the caller rejects
         * the value instead of silently treating an unknown unit as Celsius.
         */
        fun parse(raw: String?): TemperatureUnit? =
            when (raw.orEmpty().lowercase().replace("°", " ").replace(degreesWord, " ").trim()) {
                "" -> UNSPECIFIED
                "c", "celsius" -> CELSIUS
                "f", "fahrenheit" -> FAHRENHEIT
                else -> null
            }
    }
}
