package com.carevoice.contracts

import com.carevoice.testsupport.InvalidJson
import com.carevoice.testsupport.assertJsonEquals
import com.carevoice.testsupport.withField
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

/**
 * The event is the contract between two independently deployed services, so its JSON shape is asserted exactly:
 * a renamed field or a changed discriminator here would break alert-service at runtime, not at compile time.
 */
@DisplayName("ObservationRecordedEvent")
class ObservationRecordedEventTest {
    /** A fresh event per call (never mutate shared fixtures). */
    private fun event(vital: VitalPayload = VitalPayload.Pulse(bpm = 80)) = ObservationRecordedEvent(
        eventId = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        occurredAt = "2026-10-25T10:00:00Z",
        tenantId = "sunrise-care",
        wardId = "ward-a",
        residentId = "res-1",
        observationId = "00000000-0000-0000-0000-000000000001",
        vital = vital,
        source = "RULES",
        status = "CONFIRMED",
    )

    private fun eventJson(vitalJson: String = """{"type":"PULSE","bpm":80}""") =
        """
        {
          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "schemaVersion": 1,
          "occurredAt": "2026-10-25T10:00:00Z",
          "tenantId": "sunrise-care",
          "wardId": "ward-a",
          "residentId": "res-1",
          "observationId": "00000000-0000-0000-0000-000000000001",
          "vital": $vitalJson,
          "source": "RULES",
          "status": "CONFIRMED"
        }
        """.trimIndent()

    private fun decode(json: String): ObservationRecordedEvent =
        ContractJson.decodeFromString(ObservationRecordedEvent.serializer(), json)

    private fun encode(event: ObservationRecordedEvent): String =
        ContractJson.encodeToString(ObservationRecordedEvent.serializer(), event)

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @ParameterizedTest(name = "should reject an event without {0}")
        @FieldSource("com.carevoice.contracts.ContractTestValues#requiredFields")
        fun `should reject an event without a required field`(field: String) {
            assertThatExceptionOfType(SerializationException::class.java)
                .isThrownBy { decode(withField(eventJson(), field, InvalidJson.ABSENT)) }
        }

        @ParameterizedTest(name = "should reject {0} = {1}")
        @FieldSource("com.carevoice.contracts.ContractTestValues#stringFieldsWithWrongType")
        fun `should reject a string field with a value of another JSON type`(field: String, rawJsonValue: String) {
            assertThatExceptionOfType(SerializationException::class.java)
                .isThrownBy { decode(withField(eventJson(), field, rawJsonValue)) }
        }

        @ParameterizedTest(name = "should reject vital type \"{0}\"")
        @FieldSource("com.carevoice.contracts.ContractTestValues#unknownVitalTypes")
        fun `should reject a vital payload with an unknown type`(type: String) {
            assertThatExceptionOfType(SerializationException::class.java)
                .isThrownBy { decode(eventJson(vitalJson = """{"type":"$type","bpm":80}""")) }
        }

        @Test
        fun `should reject a vital payload without a type`() {
            assertThatExceptionOfType(SerializationException::class.java)
                .isThrownBy { decode(eventJson(vitalJson = """{"bpm":80}""")) }
        }
    }

    @Nested
    @DisplayName("Response Validation")
    inner class ResponseValidation {
        @ParameterizedTest(name = "should encode {0}")
        @FieldSource("com.carevoice.contracts.ContractTestValues#vitalPayloads")
        fun `should encode the exact JSON shape with the type discriminator`(vital: VitalPayload, vitalJson: String) {
            assertJsonEquals(eventJson(vitalJson), encode(event(vital)))
        }

        @ParameterizedTest(name = "should round-trip {0}")
        @FieldSource("com.carevoice.contracts.ContractTestValues#vitalPayloadTypes")
        fun `should decode what it encodes`(vital: VitalPayload) {
            val event = event(vital)

            assertThat(decode(encode(event))).isEqualTo(event)
        }

        @ParameterizedTest(name = "should decode {1}")
        @FieldSource("com.carevoice.contracts.ContractTestValues#vitalPayloads")
        fun `should decode the documented JSON into the matching payload`(vital: VitalPayload, vitalJson: String) {
            assertThat(decode(eventJson(vitalJson))).isEqualTo(event(vital))
        }

        @Test
        fun `should encode the default schema version`() {
            // encodeDefaults matters here: without it a default-valued field is silently left out of the JSON.
            val encoded = ContractJson.parseToJsonElement(encode(event())).jsonObject

            assertThat(encoded["schemaVersion"]).isEqualTo(JsonPrimitive(1))
        }

        @Test
        fun `should define the schema version and event type the services agree on`() {
            assertThat(ObservationRecordedEvent.SCHEMA_VERSION).isEqualTo(1)
            assertThat(ObservationRecordedEvent.TYPE).isEqualTo("observation.recorded")
        }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should tolerate fields added by a newer producer`() {
            val newerVital = """{"type":"PULSE","bpm":80,"rhythm":"regular"}"""
            val newerJson = withField(eventJson(vitalJson = newerVital), "priority", "\"high\"")

            assertThat(decode(newerJson)).isEqualTo(event())
        }

        @Test
        fun `should apply the default schema version when an older producer leaves it out`() {
            assertThat(decode(withField(eventJson(), "schemaVersion", InvalidJson.ABSENT))).isEqualTo(event())
        }

        @Test
        fun `should keep a schema version sent by the producer`() {
            assertThat(decode(withField(eventJson(), "schemaVersion", "2"))).isEqualTo(event().copy(schemaVersion = 2))
        }
    }
}
