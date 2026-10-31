package com.carevoice.documentation.resilience

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@DisplayName("Retry and Retry-After")
class RetryTest {
    @Nested
    @DisplayName("retry()")
    inner class RetryLoop {
        private val policy = RetryPolicy(maxAttempts = 3, base = 100.milliseconds, cap = 1.seconds, random = Random(1))

        @Test
        fun `should return the first result that is not retryable`() = runTest {
            val attempts = mutableListOf<Int>()
            val result =
                retry(policy, isRetryable = { it == "fail" }) { attempt ->
                    attempts += attempt
                    if (attempt < 1) "fail" else "ok"
                }
            assertThat(result).isEqualTo("ok")
            assertThat(attempts).containsExactly(0, 1)
        }

        @Test
        fun `should give up after maxAttempts and return the last result`() = runTest {
            var calls = 0
            val result =
                retry(policy, isRetryable = { true }) {
                    calls++
                    "fail-$calls"
                }
            assertThat(result).isEqualTo("fail-3")
            assertThat(calls).isEqualTo(3)
        }

        @Test
        fun `should wait with jittered backoff between attempts but never longer than the cap`() = runTest {
            retry(policy, isRetryable = { true }) { "fail" }
            // Two waits: at most 100 ms + 200 ms (virtual time).
            assertThat(currentTime).isBetween(0L, 300L)
        }

        @Test
        fun `should not retry exceptions`() = runTest {
            var calls = 0
            val outcome =
                runCatching {
                    retry<String>(policy, isRetryable = { true }) {
                        calls++
                        error("boom")
                    }
                }
            assertThat(outcome.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java).hasMessage("boom")
            assertThat(calls).isEqualTo(1)
        }

        @Test
        fun `should propagate cancellation instead of retrying`() = runTest {
            var calls = 0
            val outcome =
                runCatching {
                    retry<String>(policy, isRetryable = { true }) {
                        calls++
                        throw CancellationException("stop")
                    }
                }
            assertThat(outcome.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            assertThat(calls).isEqualTo(1)
        }

        @ParameterizedTest(name = "should reject maxAttempts {0}")
        @ValueSource(ints = [0, -1])
        fun `should reject a policy without attempts`(maxAttempts: Int) {
            assertThatThrownBy {
                RetryPolicy(maxAttempts, 1.seconds, 1.seconds)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    @DisplayName("RetryAfter.parse()")
    inner class RetryAfterHeader {
        private val now = Instant.parse("2026-10-21T07:28:00Z")

        // Seconds as Long: kotlin.time.Duration is a value class, which JUnit cannot inject into a test method.
        @ParameterizedTest(name = "should parse \"{0}\" as {1} s")
        @MethodSource("com.carevoice.documentation.resilience.RetryTest#validHeaders")
        fun `should parse seconds and HTTP dates`(header: String, expectedSeconds: Long) {
            assertThat(RetryAfter.parse(header, now)).isEqualTo(expectedSeconds.seconds)
        }

        @ParameterizedTest(name = "should ignore \"{0}\"")
        @MethodSource("com.carevoice.documentation.resilience.RetryTest#invalidHeaders")
        fun `should return null for absent or unparseable values`(header: String?) {
            assertThat(RetryAfter.parse(header, now)).isNull()
        }
    }

    companion object {
        @JvmStatic
        fun validHeaders(): List<Arguments> = listOf(
            Arguments.of("0", 0L),
            Arguments.of("1", 1L),
            Arguments.of(" 120 ", 120L),
            Arguments.of("600", 600L),
            Arguments.of("601", RetryAfter.MAX.inWholeSeconds),
            Arguments.of("99999999999999999999999", RetryAfter.MAX.inWholeSeconds),
            Arguments.of("Wed, 21 Oct 2026 07:28:30 GMT", 30L),
            Arguments.of("Wed, 21 Oct 2026 07:27:00 GMT", 0L),
            Arguments.of("Thu, 22 Oct 2026 07:28:00 GMT", RetryAfter.MAX.inWholeSeconds),
        )

        @JvmStatic
        fun invalidHeaders(): List<String?> =
            listOf(null, "", " ", "-1", "1.5", "soon", "2026-10-21T07:28:30Z", "Wed, 99 Oct")
    }
}
