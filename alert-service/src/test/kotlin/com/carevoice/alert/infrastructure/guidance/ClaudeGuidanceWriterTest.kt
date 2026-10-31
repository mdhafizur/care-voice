package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.GuidanceStep
import com.carevoice.alert.domain.guidance.ProtocolChunk
import com.carevoice.alert.domain.guidance.ScoredChunk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The parts of the Claude adapter that decide correctness: the prompt and the answer parsing. The HTTP call itself is
 * a plain POST; the use case's grounding check guards whatever comes back.
 */
@DisplayName("ClaudeGuidanceWriter")
class ClaudeGuidanceWriterTest {
    @Nested
    @DisplayName("Answer parsing")
    inner class AnswerParsing {
        @Test
        fun `should read the steps and their citations from a JSON answer`() {
            val steps =
                ClaudeGuidanceWriter.parse(
                    """{"steps": [{"text": "Recheck the blood pressure.", "citations": ["hypertensive-crisis#2"]}]}""",
                )

            assertThat(steps).containsExactly(
                GuidanceStep("Recheck the blood pressure.", listOf("hypertensive-crisis#2")),
            )
        }

        @Test
        fun `should tolerate text around the JSON object`() {
            val json = """{"steps": [{"text": "Stay with the resident.", "citations": ["x#1"]}]}"""
            val answer = "```json\n$json\n```"

            assertThat(ClaudeGuidanceWriter.parse(answer)).containsExactly(
                GuidanceStep("Stay with the resident.", listOf("x#1")),
            )
        }

        @Test
        fun `should return null when the model declines`() {
            assertThat(ClaudeGuidanceWriter.parse("  NO_GUIDANCE \n")).isNull()
        }

        @Test
        fun `should keep a step without citations so that the grounding check rejects it`() {
            val steps = ClaudeGuidanceWriter.parse("""{"steps": [{"text": "Do something."}]}""")

            assertThat(steps).containsExactly(GuidanceStep("Do something.", emptyList()))
        }

        @Test
        fun `should reject an answer without a JSON object`() {
            assertThatThrownBy { ClaudeGuidanceWriter.parse("Sorry, I cannot help with that.") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    @DisplayName("Messages API")
    inner class MessagesApi {
        @Test
        fun `should take the text of the first text content block`() {
            val body = """{"id": "msg_1", "content": [{"type": "text", "text": "NO_GUIDANCE"}], "stop_reason": "x"}"""

            assertThat(ClaudeGuidanceWriter.textOf(body)).isEqualTo("NO_GUIDANCE")
        }

        @Test
        fun `should put every retrieved section with its citation id into the prompt`() {
            val chunk =
                ProtocolChunk(
                    id = "hypotension#2",
                    documentId = "hypotension",
                    documentTitle = "Protocol 4.3: Hypotension",
                    section = "Immediate steps",
                    text = "Protocol 4.3: Hypotension. Immediate steps.\n- Help the resident lie down.",
                )

            val situation = "Low blood pressure. Severity: WARNING."

            val prompt = ClaudeGuidanceWriter.userPrompt(situation, listOf(ScoredChunk(chunk, 0.5)))

            assertThat(prompt)
                .contains("Alert: Low blood pressure. Severity: WARNING.")
                .contains("<excerpt id=\"hypotension#2\" protocol=\"Protocol 4.3: Hypotension\"")
                .contains("section=\"Immediate steps\">")
                .contains("- Help the resident lie down.")
        }

        @Test
        fun `should forbid unsourced content and define how to decline in the system prompt`() {
            assertThat(ClaudeGuidanceWriter.SYSTEM_PROMPT)
                .contains("ONLY the protocol excerpts")
                .contains("doses")
                .contains(ClaudeGuidanceWriter.DECLINE)
        }
    }
}
