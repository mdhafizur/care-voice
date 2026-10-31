package com.carevoice.domain

import com.carevoice.domain.extraction.RawVitalCandidate
import com.carevoice.domain.extraction.RuleConfidence
import com.carevoice.domain.model.BlankInput
import com.carevoice.domain.model.BloodPressure
import com.carevoice.domain.model.BodyTemperature
import com.carevoice.domain.model.InconsistentValues
import com.carevoice.domain.model.OutOfRange
import com.carevoice.domain.model.Pulse
import com.carevoice.domain.model.TooLong
import com.carevoice.domain.model.VitalType
import com.carevoice.testsupport.InvalidValues
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments

// Value buckets for the domain tests: every data-driven test draws from a named bucket here
// instead of hand-picking values inline, so a boundary is defined once and every test that needs it uses the same one.
// Referenced with @FieldSource("com.carevoice.domain.<Object>#<field>"); @JvmField makes each one a static field.

/** Blood pressure buckets. Pairs are (systolic, diastolic). */
object BloodPressureValues {
    /** A plausible partner value, so a bucket can vary one side of the pair while the other stays valid. */
    const val PLAUSIBLE_SYSTOLIC = 120
    const val PLAUSIBLE_DIASTOLIC = 75

    /** Both bounds of both ranges, typical readings and the closest consistent pair (diastolic one below systolic). */
    @JvmField
    val valid: List<Arguments> =
        listOf(
            arguments(50, 30),
            arguments(260, 160),
            arguments(260, 30),
            arguments(51, 50),
            arguments(161, 160),
            arguments(90, 60),
            arguments(120, 75),
            arguments(180, 120),
        )

    /** Just outside both bounds, zero, negatives and the Int extremes (an overflow must not wrap into range). */
    @JvmField
    val systolicOutOfRange: List<Int> = listOf(Int.MIN_VALUE, -120, 0, 49, 261, 999, Int.MAX_VALUE)

    @JvmField
    val diastolicOutOfRange: List<Int> = listOf(Int.MIN_VALUE, -75, 0, 29, 161, 999, Int.MAX_VALUE)

    /** Both values plausible on their own, but diastolic is not below systolic (equal, or swapped by the speaker). */
    @JvmField
    val inconsistent: List<Arguments> =
        listOf(
            arguments(50, 50),
            arguments(80, 80),
            arguments(160, 160),
            arguments(75, 120),
            arguments(60, 61),
        )
}

object PulseValues {
    @JvmField
    val valid: List<Int> = listOf(20, 21, 45, 80, 120, 249, 250)

    @JvmField
    val outOfRange: List<Int> = listOf(Int.MIN_VALUE, -80, 0, 19, 251, 400, Int.MAX_VALUE)
}

/** Body temperature buckets. Pairs are (input, value the domain stores or reports, in °C). */
object TemperatureValues {
    /** Rounded to one decimal before the range check, so 29.95 is stored (and checked) as 30.0. */
    @JvmField
    val validCelsius: List<Arguments> =
        listOf(
            arguments(30.0, 30.0),
            arguments(29.95, 30.0),
            arguments(36.8, 36.8),
            arguments(37.2, 37.2),
            arguments(37.24, 37.2),
            arguments(37.25, 37.3),
            arguments(44.0, 44.0),
            arguments(44.04, 44.0),
        )

    @JvmField
    val celsiusOutOfRange: List<Arguments> =
        listOf(
            arguments(29.94, 29.9),
            arguments(0.0, 0.0),
            arguments(-40.0, -40.0),
            arguments(44.05, 44.1),
            arguments(45.0, 45.0),
            arguments(98.6, 98.6),
            // Far beyond Int: the error must report the value that was given, not a clamped one.
            arguments(1.0E10, 1.0E10),
        )

    @JvmField
    val nonFinite: List<Double> = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

    /** Fahrenheit readings and their Celsius value, both bounds included (86 °F = 30 °C, 111.2 °F = 44 °C). */
    @JvmField
    val validFahrenheit: List<Arguments> =
        listOf(
            arguments(86.0, 30.0),
            arguments(95.0, 35.0),
            arguments(98.6, 37.0),
            arguments(100.4, 38.0),
            arguments(101.3, 38.5),
            arguments(104.0, 40.0),
            arguments(111.2, 44.0),
        )

    /** The range error reports the converted Celsius value, because the domain has one unit. */
    @JvmField
    val fahrenheitOutOfRange: List<Arguments> =
        listOf(
            arguments(85.9, 29.9),
            arguments(111.3, 44.1),
            arguments(32.0, 0.0),
            arguments(212.0, 100.0),
        )
}

/** Units as a speaker or a model might write them, see TemperatureUnit.parse. */
object TemperatureUnitValues {
    @JvmField
    val celsius: List<String> =
        listOf(
            "c",
            "C",
            "°c",
            "°C",
            "° c",
            "celsius",
            "Celsius",
            "CELSIUS",
            " celsius ",
            "degrees celsius",
            "degree Celsius",
        )

    @JvmField
    val fahrenheit: List<String> =
        listOf(
            "f",
            "F",
            "°f",
            "°F",
            "° f",
            "fahrenheit",
            "Fahrenheit",
            "FAHRENHEIT",
            "degrees fahrenheit",
            "degree Fahrenheit",
        )

    /** Nothing, or only "degrees" / "°": no scale named. */
    @JvmField
    val unspecified: List<String> = listOf("", " ", "degrees", "degree", "Degrees", "°")

    /** Never guessed as Celsius: an unknown unit is rejected instead. */
    @JvmField
    val unsupported: List<String> = listOf("kelvin", "K", "deg", "cc", "ff", "celsius fahrenheit", "rankine", "%")

    /** At or below the threshold, the value is read as Celsius (44-50 is then rejected as implausible). */
    @JvmField
    val celsiusMagnitudes: List<Double> = listOf(30.0, 37.2, 44.0, 49.9, 50.0)

    @JvmField
    val fahrenheitMagnitudes: List<Double> = listOf(50.1, 86.0, 98.6, 111.2)
}

/** Spoken transcripts and their canonical form after TranscriptNormalizer. Pairs are (spoken, normalized). */
object TranscriptValues {
    @JvmField
    val spokenVariants: List<Arguments> =
        listOf(
            arguments("BP 120 over 75", "bp 120/75"),
            arguments("BP 120 OVER 75", "bp 120/75"),
            arguments("Blood pressure 120 slash 75", "blood pressure 120/75"),
            arguments("bp 120 / 75", "bp 120/75"),
            arguments("bp 120/ 75", "bp 120/75"),
            arguments("bp 120 /75", "bp 120/75"),
            arguments("120   over   75", "120/75"),
            arguments("Temperature 37 point 2 degrees", "temperature 37.2 degrees"),
            arguments("temp 98 point 6 Fahrenheit", "temp 98.6 fahrenheit"),
            arguments("TEMPERATURE 37.2 CELSIUS", "temperature 37.2 celsius"),
            arguments("  Pulse   80  ", "pulse 80"),
            arguments("pulse\t80\nbp 120 over 80", "pulse 80 bp 120/80"),
        )

    /** Already lowercase and single-spaced, and no word stands between two digits: normalizing changes nothing. */
    @JvmField
    val wordsLeftUntouched: List<String> =
        listOf(
            "walked over to the window",
            "at this point 2 nurses came in",
            "slash and burn",
            "the pain is over",
            "over 75 years old",
            "room 12 over there",
            "fell over 3 times",
            "point 5 of the care plan",
        )

    /** A decimal comma is deliberately not rewritten: "80,90" may be a list, and guessing could invent a value. */
    @JvmField
    val decimalComma: List<String> = listOf("temp 37,2", "pulse 80,90", "temperature 37,5 degrees", "bp 120,80")
}

/** Normalized transcript fragments for the rule extractors. */
object ExtractorValues {
    /** Each text is exactly the matched text, keyword included, and reads 120/75. */
    @JvmField
    val bloodPressureWithKeyword: List<String> =
        listOf(
            "blood pressure 120/75",
            "bp 120/75",
            "bp: 120/75",
            "blood pressure is 120/75",
            "bp measured at 120/75",
        )

    /** Text before the pair that is no keyword for it: none, a clause boundary, another vital, a too-long gap. */
    @JvmField
    val bloodPressureWithoutKeyword: List<String> =
        listOf(
            "120/75",
            "measured 120/75",
            "bp not taken, 120/75",
            "bp and pulse 120/75",
            "blood pressure measured after lunch was 120/75",
            "subp 120/75",
        )

    /** A pair glued to other digits is not a blood pressure (guard 3). */
    @JvmField
    val bloodPressureNotExtracted: List<String> =
        listOf(
            "bp 120/75/80",
            "bp 80/120/75",
            "bp 1,120/75",
            "bp 1200/75",
            "bp 120/7",
            "bp 120/75.5",
            "bp 9/6",
            "no values",
        )

    /** (text, error): found as a blood pressure, then refused by validation; the text is exactly the matched text. */
    @JvmField
    val bloodPressureRejected: List<Arguments> =
        listOf(
            arguments("bp 12/10", OutOfRange("systolic", 12, 50, 260)),
            arguments("blood pressure 300/80", OutOfRange("systolic", 300, 50, 260)),
            arguments("bp 120/20", OutOfRange("diastolic", 20, 30, 160)),
            arguments("bp 75/120", InconsistentValues("diastolic (120) must be lower than systolic (75)")),
            arguments("12/10", OutOfRange("systolic", 12, 50, 260)),
        )

    /** Each text is exactly the matched text and reads 80 beats per minute. */
    @JvmField
    val pulseWithKeyword: List<String> =
        listOf("pulse 80", "heart rate 80", "hr 80", "pulse is 80", "heart rate of 80", "pulse: 80", "pulse rate 80")

    /** Guards against misattribution: whole words only, no fractions, no clause crossing, no blood pressure halves. */
    @JvmField
    val pulseNotExtracted: List<String> =
        listOf(
            "three 80",
            "shr 80",
            "hrs 80",
            "pulse 80.5",
            "pulse 80,5",
            "pulse 1000",
            "pulse 8",
            "pulse 120/80",
            "pulse not taken, 80",
            "pulse and temperature 80",
            "blood pressure 120/80",
        )

    /** (text, error): the text is exactly the matched text. */
    @JvmField
    val pulseRejected: List<Arguments> =
        listOf(
            arguments("pulse 300", OutOfRange("pulse", 300, 20, 250)),
            arguments("heart rate 15", OutOfRange("pulse", 15, 20, 250)),
            arguments("hr 999", OutOfRange("pulse", 999, 20, 250)),
        )

    /** (text, °C): a scale was named, or "degrees" with a Celsius magnitude. Each text is exactly the matched text. */
    @JvmField
    val temperatureExplicit: List<Arguments> =
        listOf(
            arguments("temperature 37.2 degrees celsius", 37.2),
            arguments("temperature 37.2 celsius", 37.2),
            arguments("temp 37.2 °c", 37.2),
            arguments("temp 37.2°c", 37.2),
            arguments("temp 37 °celsius", 37.0),
            arguments("temperature 37.2 degrees", 37.2),
            arguments("temperature 98.6 degrees fahrenheit", 37.0),
            arguments("temperature 98.6 ° fahrenheit", 37.0),
            arguments("temp 98.6 degrees f", 37.0),
            arguments("temp 98.6 °f", 37.0),
            arguments("temp 101.3 fahrenheit", 38.5),
        )

    /** (text, °C): no unit, Celsius magnitude. */
    @JvmField
    val temperatureCelsiusAssumed: List<Arguments> =
        listOf(
            arguments("temp 36.8", 36.8),
            arguments("temperature 37", 37.0),
            arguments("temperature is 38.5", 38.5),
            arguments("temp 39.25", 39.3),
        )

    /** (text, °C): no scale named but the magnitude says Fahrenheit, so a nurse has to confirm the conversion. */
    @JvmField
    val temperatureFahrenheitInferred: List<Arguments> =
        listOf(
            arguments("temp 101.3", 38.5),
            arguments("temperature 98.6", 37.0),
            arguments("temp 101.3 degrees", 38.5),
        )

    /** (text, error): the range error reports Celsius, also for a Fahrenheit reading. */
    @JvmField
    val temperatureRejected: List<Arguments> =
        listOf(
            arguments("temp 45", OutOfRange("temperature", 45.0, 30.0, 44.0)),
            arguments("temperature 47 degrees", OutOfRange("temperature", 47.0, 30.0, 44.0)),
            arguments("temp 29.9 celsius", OutOfRange("temperature", 29.9, 30.0, 44.0)),
            arguments("temp 120 fahrenheit", OutOfRange("temperature", 48.9, 30.0, 44.0)),
            arguments("temperature 44 ° fahrenheit", OutOfRange("temperature", 6.7, 30.0, 44.0)),
        )

    @JvmField
    val temperatureNotExtracted: List<String> =
        listOf(
            "temp 37,2",
            "temp 37.234",
            "temperature normal, pulse 80",
            "attempted 37",
            "temperature 3",
            "temperature 1000",
        )
}

/** Model answers for the AI validation. Pairs are (candidate, description the nurse sees). */
object AiCandidateValues {
    /** (candidate, the validated vital sign, description). A temperature without a unit is inferred like speech. */
    @JvmField
    val valid: List<Arguments> =
        listOf(
            arguments(RawVitalCandidate(VitalType.PULSE, 88.0), Pulse.of(88).assertRight(), "AI: PULSE 88"),
            arguments(
                RawVitalCandidate(VitalType.BLOOD_PRESSURE, 120.0, 75.0),
                BloodPressure.of(120, 75).assertRight(),
                "AI: BLOOD_PRESSURE 120/75",
            ),
            arguments(
                RawVitalCandidate(VitalType.BODY_TEMPERATURE, 37.2, unit = "C"),
                BodyTemperature.ofCelsius(37.2).assertRight(),
                "AI: BODY_TEMPERATURE 37.2 C",
            ),
            arguments(
                RawVitalCandidate(VitalType.BODY_TEMPERATURE, 98.6, unit = "°F"),
                BodyTemperature.ofCelsius(37.0).assertRight(),
                "AI: BODY_TEMPERATURE 98.6 °F",
            ),
            arguments(
                RawVitalCandidate(VitalType.BODY_TEMPERATURE, 37.2),
                BodyTemperature.ofCelsius(37.2).assertRight(),
                "AI: BODY_TEMPERATURE 37.2",
            ),
            arguments(
                RawVitalCandidate(VitalType.BODY_TEMPERATURE, 101.3, unit = " "),
                BodyTemperature.ofCelsius(38.5).assertRight(),
                "AI: BODY_TEMPERATURE 101.3",
            ),
        )

    @JvmField
    val validCandidates: List<RawVitalCandidate> = valid.map { it.get().first() as RawVitalCandidate }

    /** (candidate, description, error): whole numbers that the smart constructors reject. */
    @JvmField
    val implausible: List<Arguments> =
        listOf(
            arguments(RawVitalCandidate(VitalType.PULSE, 400.0), "AI: PULSE 400", OutOfRange("pulse", 400, 20, 250)),
            arguments(
                RawVitalCandidate(VitalType.BLOOD_PRESSURE, 12.0, 10.0),
                "AI: BLOOD_PRESSURE 12/10",
                OutOfRange("systolic", 12, 50, 260),
            ),
            arguments(
                RawVitalCandidate(VitalType.BLOOD_PRESSURE, 75.0, 120.0),
                "AI: BLOOD_PRESSURE 75/120",
                InconsistentValues("diastolic (120) must be lower than systolic (75)"),
            ),
            arguments(
                RawVitalCandidate(VitalType.BLOOD_PRESSURE, 120.0),
                "AI: BLOOD_PRESSURE 120",
                InconsistentValues("blood pressure needs a systolic and a diastolic value"),
            ),
            arguments(
                RawVitalCandidate(VitalType.BODY_TEMPERATURE, 45.0, unit = "celsius"),
                "AI: BODY_TEMPERATURE 45 celsius",
                OutOfRange("temperature", 45.0, 30.0, 44.0),
            ),
            arguments(
                RawVitalCandidate(VitalType.BODY_TEMPERATURE, Double.NaN, unit = "C"),
                "AI: BODY_TEMPERATURE NaN C",
                InconsistentValues("temperature must be a finite number, was NaN"),
            ),
        )

    /** Fractions, NaN and infinities: nobody says half a beat, so a model that does has made the value up. */
    @JvmField
    val notWholeNumbers: List<Double> =
        listOf(88.5, 0.1, -72.25, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

    /** Whole numbers beyond Int: converting them would clamp to Int.MAX_VALUE and report a value nobody said. */
    @JvmField
    val beyondInt: List<Arguments> =
        listOf(
            arguments(3.0E9, "3000000000"),
            arguments(-3.0E9, "-3000000000"),
            arguments(1.0E20, "1.0E20"),
        )
}

/** Confidence values. */
object ConfidenceValues {
    @JvmField
    val valid: List<Double> = listOf(0.0, 0.5, 0.79, 0.8, 1.0)

    @JvmField
    val invalid: List<Double> =
        listOf(-0.01, 1.01, -1.0, 2.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)

    /** Rule results at or above ReviewPolicy.CONFIDENCE_THRESHOLD: the threshold itself and the named levels. */
    @JvmField
    val confirmedForRules: List<Double> = listOf(0.8, RuleConfidence.CELSIUS_ASSUMED, RuleConfidence.EXPLICIT, 1.0)

    @JvmField
    val reviewedForRules: List<Double> =
        listOf(0.0, 0.5, RuleConfidence.NO_KEYWORD, RuleConfidence.FAHRENHEIT_INFERRED, 0.79)

    @JvmField
    val any: List<Double> = listOf(0.0, 0.5, 0.8, 0.95, 1.0)
}

object ResidentIdValues {
    /** Not blank, but not 1-64 letters, digits or '-': the shared identifier bucket without its blank values. */
    @JvmField
    val malformed: List<String> = InvalidValues.identifier.filterNot { it.isBlank() }
}

/** Transcript lengths around TranscriptProcessor.MAX_TRANSCRIPT_LENGTH (2,000 characters). */
object TranscriptLengthValues {
    @JvmField
    val tooLong: List<Int> = listOf(2_001, 2_500, 64 * 1_024)

    @JvmField
    val accepted: List<Int> = listOf(8, 1_999, 2_000)
}
