package com.carevoice.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** The messages end up in API problem details (rejected candidates, 422 errors), so their wording is a contract. */
@DisplayName("DomainError")
class DomainErrorTest {
    @Nested
    @DisplayName("Messages")
    inner class Messages {
        @Test
        fun `should name the field, the value and the plausible range when a value is out of range`() {
            assertThat(OutOfRange("systolic", 12, 50, 260).message)
                .isEqualTo("systolic=12 is outside the plausible range [50, 260]")
        }

        @Test
        fun `should print decimals as given when a temperature is out of range`() {
            assertThat(OutOfRange("temperature", 45.0, 30.0, 44.0).message)
                .isEqualTo("temperature=45.0 is outside the plausible range [30.0, 44.0]")
        }

        @Test
        fun `should use the given text when values are inconsistent`() {
            assertThat(InconsistentValues("diastolic (80) must be lower than systolic (75)").message)
                .isEqualTo("diastolic (80) must be lower than systolic (75)")
        }

        @Test
        fun `should name the field when input is blank`() {
            assertThat(BlankInput("transcript").message).isEqualTo("transcript must not be blank")
        }

        @Test
        fun `should name the field and the limit when input is too long`() {
            assertThat(TooLong("transcript", 2_000).message).isEqualTo("transcript must be at most 2000 characters")
        }
    }
}
