package com.carevoice.alert.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import java.time.Instant

@DisplayName("Alert rules")
class AlertRulesTest {
    private val evaluator = AlertEvaluator.default()

    private fun observation(vital: Vital, status: ObservationStatus = ObservationStatus.CONFIRMED) = Observation(
        observationId = "obs-1",
        tenantId = "sunrise-care",
        wardId = "ward-a",
        residentId = "res-1",
        vital = vital,
        source = ObservationSource.RULES,
        status = status,
        observedAt = Instant.parse("2026-10-25T10:00:00Z"),
    )

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @ParameterizedTest(name = "should raise {2} for blood pressure {0}/{1}")
        @FieldSource("com.carevoice.alert.support.AlertTestValues#bloodPressureBoundaries")
        fun `should apply the blood pressure thresholds at their boundaries`(
            systolic: Int,
            diastolic: Int,
            expected: List<AlertRuleType>,
        ) {
            val findings = evaluator.evaluate(observation(Vital.BloodPressure(systolic, diastolic)))

            assertThat(findings.map { it.rule }).containsExactlyInAnyOrderElementsOf(expected)
        }

        @ParameterizedTest(name = "should raise {1} for pulse {0}")
        @FieldSource("com.carevoice.alert.support.AlertTestValues#pulseBoundaries")
        fun `should apply the pulse thresholds at their boundaries`(bpm: Int, expected: List<AlertRuleType>) {
            val findings = evaluator.evaluate(observation(Vital.Pulse(bpm)))

            assertThat(findings.map { it.rule }).containsExactlyElementsOf(expected)
        }

        @ParameterizedTest(name = "should raise {1} for temperature {0}")
        @FieldSource("com.carevoice.alert.support.AlertTestValues#temperatureBoundaries")
        fun `should apply the temperature thresholds at their boundaries`(
            celsius: Double,
            expected: List<AlertRuleType>,
        ) {
            val findings = evaluator.evaluate(observation(Vital.BodyTemperature(celsius)))

            assertThat(findings.map { it.rule }).containsExactlyElementsOf(expected)
        }

        @Test
        fun `should give every rule the severity of the contract`() {
            val severities =
                listOf(
                    HypertensiveCrisisRule(),
                    HypotensionRule(),
                    TachycardiaRule(),
                    BradycardiaRule(),
                    HighFeverRule(),
                    HypothermiaRule(),
                ).associate { it.type to it.severity }

            assertThat(severities).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    AlertRuleType.HYPERTENSIVE_CRISIS to Severity.CRITICAL,
                    AlertRuleType.HYPOTENSION to Severity.WARNING,
                    AlertRuleType.TACHYCARDIA to Severity.WARNING,
                    AlertRuleType.BRADYCARDIA to Severity.WARNING,
                    AlertRuleType.HIGH_FEVER to Severity.CRITICAL,
                    AlertRuleType.HYPOTHERMIA to Severity.CRITICAL,
                ),
            )
        }

        @Test
        fun `should describe a confirmed value without the unconfirmed flag`() {
            val finding = evaluator.evaluate(observation(Vital.BloodPressure(185, 95))).single()

            assertThat(finding.message).isEqualTo(
                "Blood pressure 185/95 mmHg: systolic >= 180 or diastolic >= 120 (hypertensive crisis).",
            )
        }

        @Test
        fun `should flag a NEEDS_REVIEW value as unconfirmed in the message`() {
            val finding =
                evaluator.evaluate(observation(Vital.Pulse(130), ObservationStatus.NEEDS_REVIEW)).single()

            assertThat(finding.message).isEqualTo(
                "Pulse 130 bpm: above 120 (tachycardia). (unconfirmed value: needs review by a nurse)",
            )
        }

        @Test
        fun `should format temperatures with a dot regardless of the default locale`() {
            val previous = java.util.Locale.getDefault()
            try {
                java.util.Locale.setDefault(java.util.Locale.GERMANY)
                val finding = evaluator.evaluate(observation(Vital.BodyTemperature(39.8))).single()

                assertThat(finding.message).isEqualTo("Body temperature 39.8 °C: at or above 39.5 °C (high fever).")
            } finally {
                java.util.Locale.setDefault(previous)
            }
        }

        @Test
        fun `should return no findings when the composite has no rules`() {
            assertThat(AlertEvaluator(emptyList()).evaluate(observation(Vital.Pulse(200)))).isEmpty()
        }

        @Test
        fun `should create an OPEN alert at version 0 from a finding`() {
            val obs = observation(Vital.Pulse(130))
            val finding = AlertFinding(AlertRuleType.TACHYCARDIA, Severity.WARNING, "msg")
            val createdAt = Instant.parse("2026-10-25T10:00:01Z")

            val alert = Alert.open("alert-1", "event-1", obs, finding, createdAt)

            assertThat(alert).isEqualTo(
                Alert(
                    id = "alert-1",
                    tenantId = "sunrise-care",
                    wardId = "ward-a",
                    residentId = "res-1",
                    observationId = "obs-1",
                    eventId = "event-1",
                    rule = AlertRuleType.TACHYCARDIA,
                    severity = Severity.WARNING,
                    message = "msg",
                    vital = Vital.Pulse(130),
                    status = AlertStatus.OPEN,
                    observedAt = obs.observedAt,
                    createdAt = createdAt,
                    acknowledgedBy = null,
                    acknowledgedAt = null,
                    version = 0,
                ),
            )
        }
    }
}
