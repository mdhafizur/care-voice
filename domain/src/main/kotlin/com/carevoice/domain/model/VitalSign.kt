package com.carevoice.domain.model

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlin.math.floor

/**
 * The vital signs the system understands.
 *
 * Design: every type has a private constructor and a validating factory ("smart constructor").
 * The only way to obtain an instance is through validation, so an invalid vital sign cannot
 * exist anywhere in the system: not in the API layer, not in the database mapper, not in tests.
 *
 * The factories use Arrow's `either { }` builder: each `ensure(condition) { error }` is one rule, checked in order,
 * and the first rule that fails becomes the `Left`. Invalid input is an expected result, not an exception.
 */
sealed interface VitalSign {
    val type: VitalType
}

enum class VitalType { BLOOD_PRESSURE, PULSE, BODY_TEMPERATURE }

/**
 * Two values belong together, so this is a data class rather than a value class.
 *
 * @ConsistentCopyVisibility makes the generated copy() private too. Without it,
 * `bp.copy(systolic = 999)` would bypass validation.
 */
@ConsistentCopyVisibility
data class BloodPressure private constructor(val systolic: Int, val diastolic: Int) : VitalSign {
    override val type: VitalType get() = VitalType.BLOOD_PRESSURE

    /** Readable form with the unit, e.g. "120/75 mmHg". */
    override fun toString(): String = "$systolic/$diastolic mmHg"

    companion object {
        /**
         * Plausible systolic values in mmHg (millimetres of mercury, the unit of blood pressure). A value outside
         * the range is treated as implausible: it is rejected and never stored (ADR-0009).
         */
        val SYSTOLIC_RANGE = 50..260

        /** Plausible diastolic values in mmHg; see [SYSTOLIC_RANGE]. */
        val DIASTOLIC_RANGE = 30..160

        /** Checks both ranges and that diastolic is lower than systolic; the first failed rule becomes the error. */
        fun of(systolic: Int, diastolic: Int): Either<ValidationError, BloodPressure> = either {
            ensure(systolic in SYSTOLIC_RANGE) {
                OutOfRange("systolic", systolic, SYSTOLIC_RANGE.first, SYSTOLIC_RANGE.last)
            }
            ensure(diastolic in DIASTOLIC_RANGE) {
                OutOfRange("diastolic", diastolic, DIASTOLIC_RANGE.first, DIASTOLIC_RANGE.last)
            }
            ensure(diastolic < systolic) {
                InconsistentValues("diastolic ($diastolic) must be lower than systolic ($systolic)")
            }
            BloodPressure(systolic, diastolic)
        }
    }
}

/**
 * A value class: at runtime this is just an Int (no wrapper object, no allocation),
 * but at compile time a Pulse cannot be confused with a systolic value or a count.
 *
 * JVM detail: when used through the VitalSign interface the value IS boxed, because the
 * JVM needs an object reference there. Inside the domain we mostly pass the concrete type.
 */
@JvmInline
value class Pulse private constructor(val bpm: Int) : VitalSign {
    override val type: VitalType get() = VitalType.PULSE

    /** Readable form with the unit, e.g. "80 bpm". */
    override fun toString(): String = "$bpm bpm"

    companion object {
        /** Plausible pulse values in beats per minute (bpm). A value outside is rejected, never stored (ADR-0009). */
        val RANGE = 20..250

        /** Accepts only values in [RANGE] (beats per minute); anything else is an OutOfRange error. */
        fun of(bpm: Int): Either<ValidationError, Pulse> = either {
            ensure(bpm in RANGE) { OutOfRange("pulse", bpm, RANGE.first, RANGE.last) }
            Pulse(bpm)
        }
    }
}

/** Always stored in Celsius. Fahrenheit is a transformation at the boundary, not a second representation. */
@JvmInline
value class BodyTemperature private constructor(val celsius: Double) : VitalSign {
    override val type: VitalType get() = VitalType.BODY_TEMPERATURE

    /** Readable form with the unit, e.g. "37.2 °C". */
    override fun toString(): String = "$celsius °C"

    companion object {
        /**
         * Plausible body temperatures in °C. A value outside is rejected and never stored (ADR-0009).
         * `TemperatureUnit.FAHRENHEIT_THRESHOLD` depends on this range.
         */
        val RANGE_CELSIUS = 30.0..44.0

        /**
         * Rounds to one decimal (what a thermometer shows) BEFORE the range check, so the stored value and
         * the checked value are the same number: 29.96 is stored as 30.0, and 30.0 is in range.
         */
        fun ofCelsius(celsius: Double): Either<ValidationError, BodyTemperature> = either {
            // NaN or infinity can only come from a broken upstream (e.g. model output). It must be a validation
            // error like any other bad value, and NaN would otherwise slip through every range comparison.
            ensure(celsius.isFinite()) { InconsistentValues("temperature must be a finite number, was $celsius") }
            // floor(x + 0.5) rounds half up like roundToInt(), but without clamping to Int: 1e10 must be reported
            // as 1e10, not as the 214748364.7 a clamped Int would make of it.
            val rounded = floor(celsius * 10 + 0.5) / 10.0
            ensure(rounded in RANGE_CELSIUS) {
                OutOfRange("temperature", rounded, RANGE_CELSIUS.start, RANGE_CELSIUS.endInclusive)
            }
            BodyTemperature(rounded)
        }

        /**
         * The range error reports the converted Celsius value: the domain has one unit, so its messages do too.
         * The conversion is the standard one: °C = (°F - 32) * 5 / 9.
         */
        fun ofFahrenheit(fahrenheit: Double): Either<ValidationError, BodyTemperature> =
            ofCelsius((fahrenheit - 32) * 5 / 9)
    }
}
