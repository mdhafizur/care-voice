package com.carevoice.contracts

import com.carevoice.testsupport.InvalidJson
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments

// Buckets for the wire-contract tests: each vital payload and each required field is named once,
// so adding a payload type or a field to the event automatically extends every test that iterates over them.

object ContractTestValues {
    /** (payload, its exact JSON): one entry per VitalPayload subtype, discriminator included. */
    @JvmField
    val vitalPayloads: List<Arguments> =
        listOf(
            arguments(
                VitalPayload.BloodPressure(systolic = 120, diastolic = 75),
                """{"type":"BLOOD_PRESSURE","systolic":120,"diastolic":75}""",
            ),
            arguments(VitalPayload.Pulse(bpm = 80), """{"type":"PULSE","bpm":80}"""),
            arguments(VitalPayload.BodyTemperature(celsius = 37.2), """{"type":"BODY_TEMPERATURE","celsius":37.2}"""),
        )

    /** Every VitalPayload subtype on its own, for tests that need no JSON. */
    @JvmField
    val vitalPayloadTypes: List<VitalPayload> = vitalPayloads.map { it.get().first() as VitalPayload }

    /** Fields without a default: a producer that leaves one out sends an event the consumer cannot process. */
    @JvmField
    val requiredFields: List<String> =
        listOf(
            "eventId",
            "occurredAt",
            "tenantId",
            "wardId",
            "residentId",
            "observationId",
            "vital",
            "source",
            "status",
        )

    private val requiredStringFields: List<String> = requiredFields - "vital"

    /** (field, raw JSON value): every required string field combined with every non-string JSON value. */
    @JvmField
    val stringFieldsWithWrongType: List<Arguments> =
        requiredStringFields.flatMap { field -> InvalidJson.string.map { value -> arguments(field, value) } }

    /** Discriminators no version of the contract defines. */
    @JvmField
    val unknownVitalTypes: List<String> = listOf("SPO2", "blood_pressure", "BloodPressure", "")
}
