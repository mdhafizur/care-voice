package com.carevoice.domain.model

import com.carevoice.domain.common.Either
import com.carevoice.domain.common.left
import com.carevoice.domain.common.right
import kotlin.math.floor

/**
 * The vital signs the system understands.
 *
 * Design: every type has a private constructor and a validating factory ("smart constructor").
 * The only way to obtain an instance is through validation, so an invalid vital sign cannot
 * exist anywhere in the system: not in the API layer, not in the database mapper, not in tests.
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

    override fun toString(): String = "$systolic/$diastolic mmHg"

    companion object {
        val SYSTOLIC_RANGE = 50..260
        val DIASTOLIC_RANGE = 30..160

        fun of(systolic: Int, diastolic: Int): Either<ValidationError, BloodPressure> = when {
            systolic !in SYSTOLIC_RANGE ->
                OutOfRange("systolic", systolic, SYSTOLIC_RANGE.first, SYSTOLIC_RANGE.last).left()

            diastolic !in DIASTOLIC_RANGE ->
                OutOfRange("diastolic", diastolic, DIASTOLIC_RANGE.first, DIASTOLIC_RANGE.last).left()

            diastolic >= systolic ->
                InconsistentValues("diastolic ($diastolic) must be lower than systolic ($systolic)").left()

            else -> BloodPressure(systolic, diastolic).right()
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

    override fun toString(): String = "$bpm bpm"

    companion object {
        val RANGE = 20..250

        fun of(bpm: Int): Either<ValidationError, Pulse> =
            if (bpm in RANGE) Pulse(bpm).right() else OutOfRange("pulse", bpm, RANGE.first, RANGE.last).left()
    }
}

/** Always stored in Celsius. Fahrenheit is a transformation at the boundary, not a second representation. */
@JvmInline
value class BodyTemperature private constructor(val celsius: Double) : VitalSign {
    override val type: VitalType get() = VitalType.BODY_TEMPERATURE

    override fun toString(): String = "$celsius °C"

    companion object {
        val RANGE_CELSIUS = 30.0..44.0

        /**
         * Rounds to one decimal (what a thermometer shows) BEFORE the range check, so the stored value and
         * the checked value are the same number: 29.96 is stored as 30.0, and 30.0 is in range.
         */
        fun ofCelsius(celsius: Double): Either<ValidationError, BodyTemperature> {
            // NaN or infinity can only come from a broken upstream (e.g. model output). It must be a validation
            // error like any other bad value, and NaN would otherwise slip through every range comparison.
            if (!celsius.isFinite()) {
                return InconsistentValues("temperature must be a finite number, was $celsius").left()
            }
            // floor(x + 0.5) rounds half up like roundToInt(), but without clamping to Int: 1e10 must be reported
            // as 1e10, not as the 214748364.7 a clamped Int would make of it.
            val rounded = floor(celsius * 10 + 0.5) / 10.0
            return if (rounded in RANGE_CELSIUS) {
                BodyTemperature(rounded).right()
            } else {
                OutOfRange("temperature", rounded, RANGE_CELSIUS.start, RANGE_CELSIUS.endInclusive).left()
            }
        }

        /** The range error reports the converted Celsius value: the domain has one unit, so its messages do too. */
        fun ofFahrenheit(fahrenheit: Double): Either<ValidationError, BodyTemperature> =
            ofCelsius((fahrenheit - 32) * 5 / 9)
    }
}
