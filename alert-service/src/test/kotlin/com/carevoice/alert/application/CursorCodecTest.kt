package com.carevoice.alert.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.FieldSource
import java.time.Instant
import java.util.Base64

@DisplayName("CursorCodec")
class CursorCodecTest {
    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @ParameterizedTest(name = "should reject cursor {0}")
        @FieldSource("com.carevoice.alert.support.AlertTestValues#malformedCursors")
        fun `should reject a malformed cursor`(cursor: String) {
            assertThatExceptionOfType(AlertServiceException.InvalidCursor::class.java)
                .isThrownBy { CursorCodec.decode(cursor) }
        }

        @Test
        fun `should reject an empty cursor`() {
            assertThatExceptionOfType(AlertServiceException.InvalidCursor::class.java)
                .isThrownBy { CursorCodec.decode("") }
        }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @ParameterizedTest(name = "should round-trip direction {0}")
        @EnumSource(Keyset.Direction::class)
        fun `should decode what it encodes`(direction: Keyset.Direction) {
            val keyset = Keyset(Instant.parse("2026-10-25T10:00:00.123Z"), "alert-0001", direction)

            assertThat(CursorCodec.decode(CursorCodec.encode(keyset))).isEqualTo(keyset)
        }

        @Test
        fun `should encode the documented a-prefixed format as unpadded base64url`() {
            val cursor = CursorCodec.encode(Keyset(Instant.parse("2026-10-25T10:00:00Z"), "x", Keyset.Direction.OLDER))

            assertThat(cursor).doesNotContain("=", "+", "/")
            assertThat(String(Base64.getUrlDecoder().decode(cursor))).isEqualTo("a|2026-10-25T10:00:00Z|x")
        }

        @Test
        fun `should encode the documented b-prefixed format for the previous page`() {
            val cursor = CursorCodec.encode(Keyset(Instant.parse("2026-10-25T10:00:00Z"), "x", Keyset.Direction.NEWER))

            assertThat(String(Base64.getUrlDecoder().decode(cursor))).isEqualTo("b|2026-10-25T10:00:00Z|x")
        }
    }
}
