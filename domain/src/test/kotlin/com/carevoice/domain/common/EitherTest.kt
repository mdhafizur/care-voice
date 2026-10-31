package com.carevoice.domain.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Either")
class EitherTest {
    private val success: Either<String, Int> = 2.right()
    private val failure: Either<String, Int> = "boom".left()

    @Nested
    @DisplayName("Construction")
    inner class Construction {
        @Test
        fun `should wrap a value as a success`() {
            assertThat(2.right()).isEqualTo(Either.Right(2))
        }

        @Test
        fun `should wrap a value as a failure`() {
            assertThat("boom".left()).isEqualTo(Either.Left("boom"))
        }
    }

    @Nested
    @DisplayName("Transformation")
    inner class Transformation {
        @Test
        fun `should transform a success with map`() {
            assertThat(success.map { it * 10 }).isEqualTo(Either.Right(20))
        }

        @Test
        fun `should keep a failure unchanged with map`() {
            assertThat(failure.map { it * 10 }).isEqualTo(Either.Left("boom"))
        }

        @Test
        fun `should chain a success into the next step with flatMap`() {
            assertThat(success.flatMap { "too small: $it".left() }).isEqualTo(Either.Left("too small: 2"))
        }

        @Test
        fun `should not run the next step after a failure with flatMap`() {
            assertThat(failure.flatMap { (it * 10).right() }).isEqualTo(Either.Left("boom"))
        }

        @Test
        fun `should fold a success with the right-hand function`() {
            assertThat(success.fold(onLeft = { "left $it" }, onRight = { "right $it" })).isEqualTo("right 2")
        }

        @Test
        fun `should fold a failure with the left-hand function`() {
            assertThat(failure.fold(onLeft = { "left $it" }, onRight = { "right $it" })).isEqualTo("left boom")
        }
    }

    @Nested
    @DisplayName("Access")
    inner class Access {
        @Test
        fun `should return the value of a success and null for a failure with getOrNull`() {
            assertThat(listOf(success.getOrNull(), failure.getOrNull())).containsExactly(2, null)
        }

        @Test
        fun `should return the error of a failure and null for a success with leftOrNull`() {
            assertThat(listOf(failure.leftOrNull(), success.leftOrNull())).containsExactly("boom", null)
        }
    }
}
