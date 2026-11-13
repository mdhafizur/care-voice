package com.carevoice.alert.domain

import java.util.Locale

/** Stable identifiers of the rules; stored in Mongo and part of the unique `eventId + rule` key. */
enum class AlertRuleType {
    HYPERTENSIVE_CRISIS,
    HYPOTENSION,
    TACHYCARDIA,
    BRADYCARDIA,
    HIGH_FEVER,
    HYPOTHERMIA,
}

/** What a rule found: the input for [Alert.open]. */
data class AlertFinding(val rule: AlertRuleType, val severity: Severity, val message: String)

/**
 * Every clinical threshold in ONE place (docs/CONTRACTS.md, section 9), so a clinical review reads one object and a
 * change cannot leave two rules disagreeing about the same limit.
 */
object AlertThresholds {
    /** Hypertensive crisis: systolic at or above this (mmHg). */
    const val CRISIS_SYSTOLIC_MIN = 180

    /** Hypertensive crisis: diastolic at or above this (mmHg). */
    const val CRISIS_DIASTOLIC_MIN = 120

    /** Hypotension: systolic below this (mmHg). */
    const val HYPOTENSION_SYSTOLIC_BELOW = 90

    /** Tachycardia: pulse above this (bpm). */
    const val TACHYCARDIA_PULSE_ABOVE = 120

    /** Bradycardia: pulse below this (bpm). */
    const val BRADYCARDIA_PULSE_BELOW = 45

    /** High fever: temperature at or above this (°C). */
    const val HIGH_FEVER_CELSIUS_MIN = 39.5

    /** Hypothermia: temperature below this (°C). */
    const val HYPOTHERMIA_CELSIUS_BELOW = 35.0
}

/**
 * Strategy pattern: one implementation per clinical rule, all interchangeable behind this interface. Adding a rule is
 * adding a class (and registering it in [AlertEvaluator.default]); no existing rule changes, and each rule is unit
 * tested in isolation. [AlertEvaluator] is the Composite that applies many rules as if they were one.
 */
interface AlertRule {
    /** Returns what this rule found for the observation; an empty list when nothing is wrong. */
    fun evaluate(observation: Observation): List<AlertFinding>
}

/**
 * Template for a rule with one fixed [type] and [severity]: subclasses only say whether the vital violates the
 * threshold and how to describe it. The "unconfirmed value" flag is added here, once, for every rule.
 */
abstract class ThresholdRule(val type: AlertRuleType, val severity: Severity) : AlertRule {
    /** Returns a human-readable description of the violation, or null when the vital is within limits. */
    protected abstract fun describeViolation(vital: Vital): String?

    /** Fires one finding when the vital violates the limit; the message notes when the value is still unconfirmed. */
    final override fun evaluate(observation: Observation): List<AlertFinding> {
        val description = describeViolation(observation.vital) ?: return emptyList()
        val message =
            when (observation.status) {
                ObservationStatus.CONFIRMED -> description
                ObservationStatus.NEEDS_REVIEW -> "$description $UNCONFIRMED_SUFFIX"
            }
        return listOf(AlertFinding(type, severity, message))
    }

    companion object {
        /** Marks alerts raised from values a nurse has not confirmed yet (docs/CONTRACTS.md, section 9). */
        const val UNCONFIRMED_SUFFIX = "(unconfirmed value: needs review by a nurse)"
    }
}

class HypertensiveCrisisRule : ThresholdRule(AlertRuleType.HYPERTENSIVE_CRISIS, Severity.CRITICAL) {
    /** Fires when systolic or diastolic is at or above its crisis limit. */
    override fun describeViolation(vital: Vital): String? {
        if (vital !is Vital.BloodPressure) return null
        val violated =
            vital.systolic >= AlertThresholds.CRISIS_SYSTOLIC_MIN ||
                vital.diastolic >= AlertThresholds.CRISIS_DIASTOLIC_MIN
        return if (violated) {
            "Blood pressure ${vital.systolic}/${vital.diastolic} mmHg: systolic >= " +
                "${AlertThresholds.CRISIS_SYSTOLIC_MIN} or diastolic >= ${AlertThresholds.CRISIS_DIASTOLIC_MIN} " +
                "(hypertensive crisis)."
        } else {
            null
        }
    }
}

class HypotensionRule : ThresholdRule(AlertRuleType.HYPOTENSION, Severity.WARNING) {
    /** Fires when systolic is below the limit; diastolic is not checked. */
    override fun describeViolation(vital: Vital): String? =
        if (vital is Vital.BloodPressure && vital.systolic < AlertThresholds.HYPOTENSION_SYSTOLIC_BELOW) {
            "Blood pressure ${vital.systolic}/${vital.diastolic} mmHg: systolic < " +
                "${AlertThresholds.HYPOTENSION_SYSTOLIC_BELOW} (hypotension)."
        } else {
            null
        }
}

class TachycardiaRule : ThresholdRule(AlertRuleType.TACHYCARDIA, Severity.WARNING) {
    /** Fires when the pulse is above the limit. */
    override fun describeViolation(vital: Vital): String? =
        if (vital is Vital.Pulse && vital.bpm > AlertThresholds.TACHYCARDIA_PULSE_ABOVE) {
            "Pulse ${vital.bpm} bpm: above ${AlertThresholds.TACHYCARDIA_PULSE_ABOVE} (tachycardia)."
        } else {
            null
        }
}

class BradycardiaRule : ThresholdRule(AlertRuleType.BRADYCARDIA, Severity.WARNING) {
    /** Fires when the pulse is below the limit. */
    override fun describeViolation(vital: Vital): String? =
        if (vital is Vital.Pulse && vital.bpm < AlertThresholds.BRADYCARDIA_PULSE_BELOW) {
            "Pulse ${vital.bpm} bpm: below ${AlertThresholds.BRADYCARDIA_PULSE_BELOW} (bradycardia)."
        } else {
            null
        }
}

class HighFeverRule : ThresholdRule(AlertRuleType.HIGH_FEVER, Severity.CRITICAL) {
    /** Fires when the temperature is at or above the fever limit. */
    override fun describeViolation(vital: Vital): String? =
        if (vital is Vital.BodyTemperature && vital.celsius >= AlertThresholds.HIGH_FEVER_CELSIUS_MIN) {
            "Body temperature ${celsius(vital.celsius)} °C: at or above " +
                "${celsius(AlertThresholds.HIGH_FEVER_CELSIUS_MIN)} °C (high fever)."
        } else {
            null
        }
}

class HypothermiaRule : ThresholdRule(AlertRuleType.HYPOTHERMIA, Severity.CRITICAL) {
    /** Fires when the temperature is below the hypothermia limit. */
    override fun describeViolation(vital: Vital): String? =
        if (vital is Vital.BodyTemperature && vital.celsius < AlertThresholds.HYPOTHERMIA_CELSIUS_BELOW) {
            "Body temperature ${celsius(vital.celsius)} °C: below " +
                "${celsius(AlertThresholds.HYPOTHERMIA_CELSIUS_BELOW)} °C (hypothermia)."
        } else {
            null
        }
}

/** Locale-independent, so a German server never writes "39,8 °C". */
private fun celsius(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/**
 * Composite pattern: applies every rule and concatenates their findings, and is itself an [AlertRule], so callers
 * cannot tell (and need not care) whether they evaluate one rule or the whole catalogue. A blood pressure of 185/125
 * yields exactly one HYPERTENSIVE_CRISIS finding; a value can match at most one rule per vital type today, but the
 * composite does not assume that.
 */
class AlertEvaluator(private val rules: List<AlertRule>) : AlertRule {
    /** Runs every rule and joins their findings, in rule order. */
    override fun evaluate(observation: Observation): List<AlertFinding> = rules.flatMap { it.evaluate(observation) }

    companion object {
        /** The rule catalogue of docs/CONTRACTS.md, section 9. */
        fun default(): AlertEvaluator = AlertEvaluator(
            listOf(
                HypertensiveCrisisRule(),
                HypotensionRule(),
                TachycardiaRule(),
                BradycardiaRule(),
                HighFeverRule(),
                HypothermiaRule(),
            ),
        )
    }
}
