package com.carevoice.alert.application

import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.WardScope
import com.carevoice.alert.infrastructure.guidance.ClasspathProtocolSource
import com.carevoice.alert.infrastructure.guidance.HashingEmbedder
import com.carevoice.alert.support.GuidanceTestAlerts
import com.carevoice.alert.support.InMemoryAlerts
import com.carevoice.alert.support.InMemoryProtocolChunks
import com.carevoice.alert.support.ScriptedGuidanceWriter
import com.carevoice.alert.support.TestFixtures
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GetAlertGuidance")
class GetAlertGuidanceTest {
    private val embedder = HashingEmbedder()
    private val knowledgeBase =
        InMemoryProtocolChunks().also { chunks ->
            runBlocking { IngestProtocols(ClasspathProtocolSource(), embedder, chunks).execute() }
        }
    private val scope = WardScope(TestFixtures.TENANT, TestFixtures.WARD)

    private fun execute(
        alert: Alert = GuidanceTestAlerts.alert(),
        chunks: ProtocolChunkRepository? = knowledgeBase,
        writer: GuidanceWriter? = null,
        embedder: Embedder = this.embedder,
    ): AlertGuidance = runBlocking {
        GetAlertGuidance(InMemoryAlerts(alert), embedder, chunks, writer).execute(scope, alert.id)
    }

    @Nested
    @DisplayName("Retrieval")
    inner class Retrieval {
        @Test
        fun `should retrieve every section of the matching protocol`() {
            val guidance = execute()

            assertThat(guidance.status).isEqualTo(GuidanceStatus.FOUND)
            assertThat(guidance.sources.map { it.chunk.id }).containsExactly(
                "hypertensive-crisis#1",
                "hypertensive-crisis#2",
                "hypertensive-crisis#3",
                "hypertensive-crisis#4",
            )
        }

        @Test
        fun `should put the review protocol first for an alert on an unconfirmed value`() {
            val guidance = execute(GuidanceTestAlerts.alert(unconfirmed = true))

            assertThat(guidance.sources.map { it.chunk.documentId }.distinct())
                .containsExactly("unconfirmed-values", "hypertensive-crisis")
        }

        @Test
        fun `should answer NO_MATCHING_PROTOCOL with no steps or sources when there is no knowledge base`() {
            val guidance = execute(chunks = null)

            assertThat(guidance.status).isEqualTo(GuidanceStatus.NO_MATCHING_PROTOCOL)
            assertThat(guidance.generatedBy).isNull()
            assertThat(guidance.steps).isEmpty()
            assertThat(guidance.sources).isEmpty()
        }
    }

    @Nested
    @DisplayName("Generation and grounding")
    inner class Generation {
        @Test
        fun `should copy the actionable protocol steps, each citing its section, when no writer is configured`() {
            val guidance = execute()

            assertThat(guidance.generatedBy).isEqualTo(GuidanceGenerator.EXTRACTIVE)
            assertThat(guidance.steps.first()).isEqualTo(
                GuidanceStep(
                    "Recheck the blood pressure after five minutes of rest, seated, on the other arm, " +
                        "with a correctly sized cuff.",
                    listOf("hypertensive-crisis#2"),
                ),
            )
            assertThat(guidance.steps).hasSize(6).allMatch {
                it.citations.single() in setOf("hypertensive-crisis#2", "hypertensive-crisis#3")
            }
        }

        @Test
        fun `should use the writer's steps when every citation is a retrieved section`() {
            val steps = listOf(GuidanceStep("Recheck the blood pressure now.", listOf("hypertensive-crisis#2")))

            val guidance = execute(writer = ScriptedGuidanceWriter { steps })

            assertThat(guidance.generatedBy).isEqualTo(GuidanceGenerator.CLAUDE)
            assertThat(guidance.steps).isEqualTo(steps)
        }

        @Test
        fun `should give the writer exactly the retrieved sections`() {
            val writer = ScriptedGuidanceWriter { null }

            val guidance = execute(writer = writer)

            assertThat(writer.receivedSources.map { it.chunk.id }).isEqualTo(guidance.sources.map { it.chunk.id })
        }

        @Test
        fun `should fall back to the protocol steps when the writer cites a section that was not retrieved`() {
            val guidance =
                execute(
                    writer = ScriptedGuidanceWriter { listOf(GuidanceStep("Lie down.", listOf("hypotension#2"))) },
                )

            assertThat(guidance.generatedBy).isEqualTo(GuidanceGenerator.EXTRACTIVE)
        }

        @Test
        fun `should fall back to the protocol steps when a step cites nothing`() {
            val guidance = execute(writer = ScriptedGuidanceWriter { listOf(GuidanceStep("Trust me.", emptyList())) })

            assertThat(guidance.generatedBy).isEqualTo(GuidanceGenerator.EXTRACTIVE)
        }

        @Test
        fun `should fall back to the protocol steps when the writer declines`() {
            assertThat(execute(writer = ScriptedGuidanceWriter { null }).generatedBy)
                .isEqualTo(GuidanceGenerator.EXTRACTIVE)
        }

        @Test
        fun `should fall back to the protocol steps when the writer fails`() {
            val guidance = execute(writer = ScriptedGuidanceWriter { error("Claude API failed with HTTP 529") })

            assertThat(guidance.generatedBy).isEqualTo(GuidanceGenerator.EXTRACTIVE)
            assertThat(guidance.steps).isNotEmpty()
        }
    }

    @Nested
    @DisplayName("Errors")
    inner class Errors {
        @Test
        fun `should report an alert of another ward as not found`() {
            val alert = GuidanceTestAlerts.alert(wardId = TestFixtures.OTHER_WARD)

            assertThatThrownBy { execute(alert) }.isInstanceOf(AlertServiceException.AlertNotFound::class.java)
        }

        @Test
        fun `should report the embedding service as unavailable when embedding the query fails`() {
            val failing =
                object : Embedder by embedder {
                    override suspend fun embedQuery(text: String): FloatArray = error("connection refused")
                }

            assertThatThrownBy { execute(embedder = failing) }
                .isInstanceOf(AlertServiceException.DependencyUnavailable::class.java)
        }
    }
}
