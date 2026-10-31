package com.carevoice.alert.domain.guidance

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ProtocolChunker")
class ProtocolChunkerTest {
    private val markdown =
        """
        # Protocol 9.9: Test protocol

        Notice text before the first section.

        ## When this protocol applies
        Applies when the test value is high.

        ## Immediate steps
        - Recheck the value.
        - Inform the ward lead.
        """.trimIndent()

    @Nested
    @DisplayName("Sections")
    inner class Sections {
        @Test
        fun `should create one chunk per section with stable citation ids`() {
            val chunks = ProtocolChunker.chunk("test-protocol", markdown)

            assertThat(chunks.map { it.id }).containsExactly("test-protocol#1", "test-protocol#2")
            assertThat(chunks.map { it.section }).containsExactly("When this protocol applies", "Immediate steps")
            assertThat(chunks).allMatch { it.documentId == "test-protocol" && it.documentTitle == TITLE }
        }

        @Test
        fun `should prefix the text with the document title and section as retrieval context`() {
            val chunk = ProtocolChunker.chunk("test-protocol", markdown).last()

            assertThat(chunk.text).isEqualTo("$TITLE. Immediate steps.\n- Recheck the value.\n- Inform the ward lead.")
        }

        @Test
        fun `should not chunk the text before the first section`() {
            assertThat(ProtocolChunker.chunk("test-protocol", markdown)).noneMatch { it.text.contains("Notice") }
        }
    }

    @Nested
    @DisplayName("Long sections")
    inner class LongSections {
        @Test
        fun `should split a section longer than the limit at blank lines and keep numbering the ids`() {
            val paragraph = "x".repeat(500)
            val long = "# $TITLE\n\n## Long\n$paragraph\n\n$paragraph\n\n$paragraph\n\n## Short\nDone."

            val chunks = ProtocolChunker.chunk("long", long)

            assertThat(chunks.map { it.id }).containsExactly("long#1", "long#2", "long#3")
            assertThat(chunks.map { it.section }).containsExactly("Long", "Long", "Short")
            assertThat(chunks.dropLast(1)).allMatch {
                it.text.substringAfter('\n').length <= ProtocolChunker.MAX_CHARS
            }
        }
    }

    @Nested
    @DisplayName("Invalid documents")
    inner class InvalidDocuments {
        @Test
        fun `should reject a document without a title line`() {
            assertThatThrownBy { ProtocolChunker.chunk("no-title", "## Section\nText") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("no '# ' title")
        }

        @Test
        fun `should reject a document id that is not lowercase kebab-case`() {
            assertThatThrownBy { ProtocolChunker.chunk("Bad Id", markdown) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private companion object {
        const val TITLE = "Protocol 9.9: Test protocol"
    }
}
