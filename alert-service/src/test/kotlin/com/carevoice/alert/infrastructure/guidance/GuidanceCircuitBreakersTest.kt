package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.GuidanceGenerator
import com.carevoice.alert.application.GuidanceStep
import com.carevoice.alert.application.GuidanceWriter
import com.carevoice.alert.domain.guidance.ScoredChunk
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

@DisplayName("Guidance circuit breakers")
class GuidanceCircuitBreakersTest {
    private val registry = SimpleMeterRegistry()

    /** A writer that answers with [answer] and counts how often the provider was really called. */
    private class FakeWriter(var answer: () -> List<GuidanceStep>?) : GuidanceWriter {
        var calls = 0
        override val generator = GuidanceGenerator.GEMINI

        override suspend fun write(situation: String, sources: List<ScoredChunk>): List<GuidanceStep>? {
            calls++
            return answer()
        }
    }

    private fun guardedWriter(fake: FakeWriter) =
        CircuitBreakingGuidanceWriter(fake, GuidanceCircuitBreakers.create(GuidanceCircuitBreakers.WRITER, registry))

    @Test
    fun `should skip the provider immediately once enough calls failed`() {
        val fake = FakeWriter { throw IOException("provider down") }
        val writer = guardedWriter(fake)

        repeat(5) { assertThatThrownBy { runBlocking { writer.write("BP 182/95", emptyList()) } } }

        assertThat(writer.circuitBreaker.state).isEqualTo(CircuitBreaker.State.OPEN)
        assertThatThrownBy { runBlocking { writer.write("BP 182/95", emptyList()) } }
            .isInstanceOf(CallNotPermittedException::class.java)
        assertThat(fake.calls).isEqualTo(5)
    }

    @Test
    fun `should count a declined answer as a healthy provider`() {
        val writer = guardedWriter(FakeWriter { null })

        repeat(10) { assertThat(runBlocking { writer.write("BP 182/95", emptyList()) }).isNull() }

        assertThat(writer.circuitBreaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
        assertThat(writer.circuitBreaker.metrics.numberOfFailedCalls).isZero()
    }

    @Test
    fun `should not count cancellation as a failure`() {
        val writer = guardedWriter(FakeWriter { throw CancellationException("request deadline") })

        repeat(10) {
            assertThatThrownBy { runBlocking { writer.write("BP 182/95", emptyList()) } }
                .isInstanceOf(CancellationException::class.java)
        }

        assertThat(writer.circuitBreaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
        assertThat(writer.circuitBreaker.metrics.numberOfBufferedCalls).isZero()
    }
}
