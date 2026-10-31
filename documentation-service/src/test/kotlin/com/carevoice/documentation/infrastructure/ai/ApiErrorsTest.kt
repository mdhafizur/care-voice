package com.carevoice.documentation.infrastructure.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ApiErrors: error code for the log line")
class ApiErrorsTest {
    @Test
    fun `should read Gemini's error status`() {
        val body = """{"error":{"code":429,"message":"Quota exceeded for metric ...","status":"RESOURCE_EXHAUSTED"}}"""

        assertThat(ApiErrors.code(body)).isEqualTo("RESOURCE_EXHAUSTED")
    }

    @Test
    fun `should read Anthropic's error type`() {
        val body = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""

        assertThat(ApiErrors.code(body)).isEqualTo("overloaded_error")
    }

    @Test
    fun `should never return the message or anything that is not a short code`() {
        assertThat(ApiErrors.code("""{"error":{"message":"transcript: pulse 80"}}""")).isNull()
        assertThat(ApiErrors.code("""{"error":{"status":"pulse 80, temp 37"}}""")).isNull()
        assertThat(ApiErrors.code("<html>Bad Gateway</html>")).isNull()
    }
}
