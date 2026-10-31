package com.carevoice.documentation.resilience

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

@DisplayName("Backoff (exponential, full jitter)")
class BackoffTest {
    private val base = 500.milliseconds
    private val cap = 60.seconds

    @Nested
    @DisplayName("Ceiling")
    inner class Ceiling {
        // Plain Long parameters: kotlin.time.Duration is a value class, which JUnit cannot inject into a test method.
        @ParameterizedTest(name = "should return {1} ms for attempt {0}")
        @MethodSource("com.carevoice.documentation.resilience.BackoffTest#ceilings")
        fun `should double the window per attempt until the cap`(attempt: Int, expectedMillis: Long) {
            assertThat(Backoff.ceiling(attempt, base, cap)).isEqualTo(expectedMillis.milliseconds)
        }

        @ParameterizedTest(name = "should stay at the cap for attempt {0}")
        @ValueSource(ints = [7, 20, 62, 63, 64, 1_000, Int.MAX_VALUE])
        fun `should never overflow for large attempts`(attempt: Int) {
            assertThat(Backoff.ceiling(attempt, base, cap)).isEqualTo(cap)
        }
    }

    @Nested
    @DisplayName("Full jitter")
    inner class FullJitter {
        @ParameterizedTest(name = "should stay within [0, ceiling] for attempt {0}")
        @ValueSource(ints = [0, 1, 2, 5, 9, 30])
        fun `should stay within the window`(attempt: Int) {
            val random = Random(42)
            val ceiling = Backoff.ceiling(attempt, base, cap)
            repeat(1_000) {
                assertThat(Backoff.fullJitter(attempt, base, cap, random)).isBetween(Duration.ZERO, ceiling)
            }
        }

        @Test
        fun `should reach both ends of the window`() {
            val lowest = Backoff.fullJitter(3, base, cap, FixedRandom(0))
            val highest = Backoff.fullJitter(3, base, cap, FixedRandom(Long.MAX_VALUE))
            assertThat(lowest).isEqualTo(Duration.ZERO)
            assertThat(highest).isEqualTo(4.seconds)
        }

        @Test
        fun `should spread retries over the whole window instead of clustering them`() {
            val random = Random(7)
            val ceiling = Backoff.ceiling(4, base, cap)
            val samples = List(2_000) { Backoff.fullJitter(4, base, cap, random) }
            assertThat(samples.count { it < ceiling / 2 }).isBetween(800, 1_200)
            assertThat(samples.distinct()).hasSizeGreaterThan(1_900)
        }
    }

    @Nested
    @DisplayName("Validation")
    inner class Validation {
        @Test
        fun `should reject a negative attempt`() {
            assertThatThrownBy { Backoff.ceiling(-1, base, cap) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @ParameterizedTest(name = "should reject a base of {0} ns")
        @ValueSource(longs = [0, -1, -5_000_000_000])
        fun `should reject a base that is not positive`(invalidBaseNanos: Long) {
            assertThatThrownBy { Backoff.ceiling(0, invalidBaseNanos.nanoseconds, cap) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `should reject a cap below the base`() {
            assertThatThrownBy { Backoff.ceiling(0, 2.seconds, 1.seconds) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `should reject an infinite cap`() {
            assertThatThrownBy { Backoff.ceiling(0, base, Duration.INFINITE) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    /** Returns the given value clamped into the requested range, so tests can pin the jitter to either end. */
    private class FixedRandom(private val value: Long) : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextLong(from: Long, until: Long): Long = value.coerceIn(from, until - 1)
    }

    companion object {
        @JvmStatic
        fun ceilings(): List<Arguments> = listOf(
            Arguments.of(0, 500L),
            Arguments.of(1, 1_000L),
            Arguments.of(2, 2_000L),
            Arguments.of(3, 4_000L),
            Arguments.of(6, 32_000L),
            Arguments.of(7, 60_000L),
        )
    }
}
