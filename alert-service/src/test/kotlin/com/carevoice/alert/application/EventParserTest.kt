package com.carevoice.alert.application

import com.carevoice.alert.support.Events
import com.carevoice.testsupport.InvalidJson
import com.carevoice.testsupport.withField
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

@DisplayName("EventParser")
class EventParserTest {
    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @Test
        fun `should reject a body that is not JSON`() {
            assertThatExceptionOfType(AlertServiceException.MalformedEvent::class.java)
                .isThrownBy { EventParser.parse("{not json") }
        }

        @Test
        fun `should reject a JSON value that is not an object`() {
            assertThatExceptionOfType(AlertServiceException.MalformedEvent::class.java)
                .isThrownBy { EventParser.parse("[1,2]") }
        }

        @ParameterizedTest(name = "should return 400 when schemaVersion is {0}")
        @FieldSource("com.carevoice.alert.support.AlertTestValues#nonIntegerSchemaVersions")
        fun `should reject a schemaVersion that is not an integer as malformed`(raw: String) {
            assertThatExceptionOfType(AlertServiceException.MalformedEvent::class.java)
                .isThrownBy { EventParser.parse(withField(Events.json(), "schemaVersion", raw)) }
        }

        @ParameterizedTest(name = "should return 422 when schemaVersion is {0}")
        @FieldSource("com.carevoice.alert.support.AlertTestValues#unsupportedSchemaVersions")
        fun `should reject an unknown schemaVersion before decoding the rest`(version: Int) {
            // The rest of the body is broken on purpose: an unknown version must win over "malformed".
            val body = withField(withField(Events.json(), "schemaVersion", "$version"), "vital", "\"changed\"")

            assertThatExceptionOfType(AlertServiceException.UnsupportedSchemaVersion::class.java)
                .isThrownBy { EventParser.parse(body) }
        }

        @ParameterizedTest(name = "should return 400 when eventId is {0}")
        @FieldSource("com.carevoice.alert.application.EventParserValues#requiredString")
        fun `should reject a required string field with a wrong type`(raw: String) {
            assertThatExceptionOfType(AlertServiceException.MalformedEvent::class.java)
                .isThrownBy { EventParser.parse(withField(Events.json(), "eventId", raw)) }
        }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should parse a valid event`() {
            val event = Events.event()

            assertThat(EventParser.parse(Events.json(event))).isEqualTo(event)
        }

        @Test
        fun `should default an absent schemaVersion to the current version`() {
            val event = Events.event()

            assertThat(EventParser.parse(withField(Events.json(event), "schemaVersion", InvalidJson.ABSENT)))
                .isEqualTo(event)
        }

        @Test
        fun `should accept the supported version through requireSupported`() {
            EventParser.requireSupported(1)
        }
    }
}

/** Reject list for a required string field of the event. */
object EventParserValues {
    @JvmField
    val requiredString: List<String> = InvalidJson.required(InvalidJson.string)
}
