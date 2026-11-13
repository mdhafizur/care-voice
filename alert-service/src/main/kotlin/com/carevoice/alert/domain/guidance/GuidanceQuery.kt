package com.carevoice.alert.domain.guidance

import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.AlertRuleType
import com.carevoice.alert.domain.Vital

/** One retrieval query; [aspect] says which part of the situation it covers (shown in logs and tests). */
data class RetrievalQuery(val aspect: Aspect, val text: String) {
    enum class Aspect { CLINICAL, REVIEW }
}

/**
 * Builds the retrieval queries for an alert. Deterministic and free of personal data: no resident id or name is ever
 * part of a query.
 *
 * Multi-query retrieval: an alert on an unconfirmed value has two aspects, the clinical situation and "check the value
 * before acting". One combined query lets the stronger clinical terms drown the review aspect, so each aspect gets its
 * own query and its own protocol.
 */
object GuidanceQuery {
    private val ruleTerms: Map<AlertRuleType, String> =
        mapOf(
            AlertRuleType.HYPERTENSIVE_CRISIS to "hypertensive crisis very high blood pressure",
            AlertRuleType.HYPOTENSION to "hypotension low blood pressure",
            AlertRuleType.TACHYCARDIA to "tachycardia fast pulse high heart rate",
            AlertRuleType.BRADYCARDIA to "bradycardia slow pulse low heart rate",
            AlertRuleType.HIGH_FEVER to "high fever elevated body temperature",
            AlertRuleType.HYPOTHERMIA to "hypothermia low body temperature",
        )

    const val REVIEW_QUERY = "unconfirmed value needs review low confidence recheck the measurement"

    /**
     * Always one clinical query from the rule, vital and severity.
     * A second review query is added when the alert message says the value is unconfirmed.
     */
    fun forAlert(alert: Alert): List<RetrievalQuery> = buildList {
        val severity = alert.severity.name.lowercase()
        val clinical = "${ruleTerms.getValue(alert.rule)}. ${describe(alert.vital)}. $severity alert"
        add(RetrievalQuery(RetrievalQuery.Aspect.CLINICAL, clinical))
        // "unconfirmed value" is part of ThresholdRule.UNCONFIRMED_SUFFIX, the marker of a value not yet confirmed.
        if (alert.message.contains("unconfirmed value", ignoreCase = true)) {
            add(RetrievalQuery(RetrievalQuery.Aspect.REVIEW, REVIEW_QUERY))
        }
    }

    /** Describes the measured value in words and units, e.g. `pulse 130 bpm heart rate`. */
    private fun describe(vital: Vital): String = when (vital) {
        is Vital.BloodPressure -> "blood pressure ${vital.systolic}/${vital.diastolic} mmHg"
        is Vital.Pulse -> "pulse ${vital.bpm} bpm heart rate"
        is Vital.BodyTemperature -> "body temperature ${vital.celsius} °C"
    }
}
