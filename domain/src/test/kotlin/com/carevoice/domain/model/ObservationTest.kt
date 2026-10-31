package com.carevoice.domain.model

import com.carevoice.domain.assertRight
import com.carevoice.domain.common.left
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import java.time.Instant
import java.util.UUID

@DisplayName("Observation value types")
class ObservationTest {
    @Nested
    @DisplayName("ResidentId")
    inner class ResidentIdTest {
        @ParameterizedTest(name = "should accept \"{0}\"")
        @FieldSource("com.carevoice.testsupport.ValidValues#identifier")
        fun `should accept letters, digits and dashes up to 64 characters`(raw: String) {
            val residentId = ResidentId.of(raw).assertRight()

            assertThat(residentId.value).isEqualTo(raw)
            assertThat(residentId.toString()).isEqualTo(raw)
        }

        @ParameterizedTest(name = "should reject blank \"{0}\"")
        @FieldSource("com.carevoice.testsupport.InvalidValues#emptyStrings")
        fun `should reject a blank resident id`(raw: String) {
            assertThat(ResidentId.of(raw)).isEqualTo(BlankInput("residentId").left())
        }

        @ParameterizedTest(name = "should reject \"{0}\"")
        @FieldSource("com.carevoice.domain.ResidentIdValues#malformed")
        fun `should reject a resident id that is not 1-64 letters, digits or dashes`(raw: String) {
            assertThat(ResidentId.of(raw))
                .isEqualTo(InconsistentValues("residentId must be 1-64 letters, digits or '-'").left())
        }
    }

    @Nested
    @DisplayName("ObservationId")
    inner class ObservationIdTest {
        @ParameterizedTest(name = "should print {0}")
        @FieldSource("com.carevoice.testsupport.ValidValues#uuid")
        fun `should print the plain UUID`(raw: String) {
            assertThat(ObservationId(UUID.fromString(raw)).toString()).isEqualTo(raw)
        }

        @Test
        fun `should generate a different id on every call`() {
            assertThat(ObservationId.random()).isNotEqualTo(ObservationId.random())
        }
    }

    @Nested
    @DisplayName("Confidence")
    inner class ConfidenceTest {
        @ParameterizedTest(name = "should accept {0}")
        @FieldSource("com.carevoice.domain.ConfidenceValues#valid")
        fun `should accept a value between zero and one`(value: Double) {
            assertThat(Confidence(value).value).isEqualTo(value)
        }

        @ParameterizedTest(name = "should reject {0}")
        @FieldSource("com.carevoice.domain.ConfidenceValues#invalid")
        fun `should fail fast on a value outside zero and one because only our own code creates it`(value: Double) {
            assertThatIllegalArgumentException()
                .isThrownBy { Confidence(value) }
                .withMessage("confidence must be in [0, 1], was $value")
        }
    }

    @Nested
    @DisplayName("Observation")
    inner class ObservationDataTest {
        @Test
        fun `should be equal to an observation with the same values`() {
            val id = ObservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            val residentId = ResidentId.of("res-1").assertRight()
            val vital = Pulse.of(80).assertRight()
            val recordedAt = Instant.parse("2026-10-25T10:00:00Z")

            fun observation() = Observation(
                id = id,
                residentId = residentId,
                vital = vital,
                recordedAt = recordedAt,
                source = ExtractionSource.RULES,
                confidence = Confidence(0.95),
                status = ObservationStatus.CONFIRMED,
            )

            assertThat(observation()).isEqualTo(observation())
        }
    }
}
