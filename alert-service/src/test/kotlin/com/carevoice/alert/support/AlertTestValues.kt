package com.carevoice.alert.support

import com.carevoice.alert.domain.AlertRuleType
import com.carevoice.testsupport.InvalidValues
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments

/**
 * alert-service buckets: every threshold is tested at, just below and just above its boundary, and
 * every invalid query value comes from here instead of being hand-picked inside a test.
 */
object AlertTestValues {
    /** (systolic, diastolic, expected rules). HYPERTENSIVE_CRISIS: systolic >= 180 or diastolic >= 120. */
    @JvmField
    val bloodPressureBoundaries: List<Arguments> =
        listOf(
            arguments(179, 119, emptyList<AlertRuleType>()),
            arguments(180, 80, listOf(AlertRuleType.HYPERTENSIVE_CRISIS)),
            arguments(181, 80, listOf(AlertRuleType.HYPERTENSIVE_CRISIS)),
            arguments(140, 120, listOf(AlertRuleType.HYPERTENSIVE_CRISIS)),
            arguments(140, 121, listOf(AlertRuleType.HYPERTENSIVE_CRISIS)),
            arguments(185, 125, listOf(AlertRuleType.HYPERTENSIVE_CRISIS)),
            // HYPOTENSION: systolic < 90
            arguments(91, 60, emptyList<AlertRuleType>()),
            arguments(90, 60, emptyList<AlertRuleType>()),
            arguments(89, 60, listOf(AlertRuleType.HYPOTENSION)),
            // A diastolic crisis with a low systolic matches both rules; the composite keeps both findings.
            arguments(85, 120, listOf(AlertRuleType.HYPERTENSIVE_CRISIS, AlertRuleType.HYPOTENSION)),
        )

    /** (bpm, expected rules). TACHYCARDIA: > 120, BRADYCARDIA: < 45. */
    @JvmField
    val pulseBoundaries: List<Arguments> =
        listOf(
            arguments(119, emptyList<AlertRuleType>()),
            arguments(120, emptyList<AlertRuleType>()),
            arguments(121, listOf(AlertRuleType.TACHYCARDIA)),
            arguments(46, emptyList<AlertRuleType>()),
            arguments(45, emptyList<AlertRuleType>()),
            arguments(44, listOf(AlertRuleType.BRADYCARDIA)),
        )

    /** (celsius, expected rules). HIGH_FEVER: >= 39.5, HYPOTHERMIA: < 35.0. */
    @JvmField
    val temperatureBoundaries: List<Arguments> =
        listOf(
            arguments(39.4, emptyList<AlertRuleType>()),
            arguments(39.5, listOf(AlertRuleType.HIGH_FEVER)),
            arguments(39.6, listOf(AlertRuleType.HIGH_FEVER)),
            arguments(35.1, emptyList<AlertRuleType>()),
            arguments(35.0, emptyList<AlertRuleType>()),
            arguments(34.9, listOf(AlertRuleType.HYPOTHERMIA)),
        )

    /** `?limit=` values outside 1..100 or not integers. */
    @JvmField
    val invalidLimits: List<String> = listOf("0", "-1", "101", "1000", "abc", "1.5", "", " ", "2147483648")

    /** `?limit=` values that must be accepted, both bounds included. */
    @JvmField
    val validLimits: List<String> = listOf("1", "20", "100")

    /** `?status=` values that are not an AlertStatus (case-sensitive). */
    @JvmField
    val invalidStatuses: List<String> = listOf("open", "Open", "CLOSED", "", "OPEN,ACKNOWLEDGED")

    /** Cursors that are not base64url of `a|<instant>|<id>` / `b|<instant>|<id>`. */
    @JvmField
    val malformedCursors: List<String> =
        listOf(
            "!!!not-base64!!!",
            b64("x|2026-10-25T10:00:00Z|alert-1"),
            b64("a|not-an-instant|alert-1"),
            b64("a|2026-10-25T10:00:00Z"),
            b64("a|2026-10-25T10:00:00Z| "),
            b64("a|2026-10-25T10:00:00Z|" + "x".repeat(65)),
            "a".repeat(513),
        )

    /** Invalid incoming X-Request-Id values that must be replaced by a generated one (the shared bucket). */
    @JvmField
    val invalidRequestIds: List<String> = InvalidValues.identifier

    /** Unknown schema versions (422 on HTTP, dead-lettered on AMQP). */
    @JvmField
    val unsupportedSchemaVersions: List<Int> = listOf(0, 2, -1, 99)

    /** Raw JSON for `schemaVersion` that is not an integer (400: malformed). */
    @JvmField
    val nonIntegerSchemaVersions: List<String> = listOf("\"1\"", "1.5", "true", "[]", "{}", "null")

    private fun b64(raw: String): String = java.util.Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.toByteArray())
}
