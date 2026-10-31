package com.carevoice.alert.domain.guidance

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Retriever and Vectors")
class RetrieverTest {
    private fun chunk(id: String, vararg vector: Float): EmbeddedChunk {
        val documentId = id.substringBefore('#')
        val chunk = ProtocolChunk(id, documentId, "Title $documentId", "Section", "text")
        return EmbeddedChunk(chunk, floatArrayOf(*vector))
    }

    private val corpus =
        listOf(
            chunk("a#1", 1f, 0f),
            chunk("a#2", 0f, 1f),
            chunk("b#1", 0.6f, 0.8f),
            chunk("b#2", 0.8f, 0.6f),
        )

    @Nested
    @DisplayName("Vectors")
    inner class VectorMath {
        @Test
        fun `should scale a vector to unit length`() {
            val normalized = Vectors.normalize(floatArrayOf(3f, 4f))

            assertThat(normalized[0].toDouble()).isCloseTo(0.6, within(1e-6))
            assertThat(normalized[1].toDouble()).isCloseTo(0.8, within(1e-6))
        }

        @Test
        fun `should keep a zero vector zero and score it 0 against anything`() {
            val zero = Vectors.normalize(floatArrayOf(0f, 0f))

            assertThat(zero).containsExactly(0f, 0f)
            assertThat(Vectors.cosine(zero, floatArrayOf(1f, 0f))).isEqualTo(0.0)
        }

        @Test
        fun `should score identical directions 1 and orthogonal ones 0`() {
            assertThat(Vectors.cosine(floatArrayOf(2f, 0f), floatArrayOf(1f, 0f))).isCloseTo(1.0, within(1e-9))
            assertThat(Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))).isCloseTo(0.0, within(1e-9))
        }
    }

    @Nested
    @DisplayName("Top k")
    inner class TopK {
        @Test
        fun `should return the k most similar chunks, best first`() {
            val result = Retriever.topK(floatArrayOf(1f, 0f), corpus, k = 2, minScore = 0.0)

            assertThat(result.map { it.chunk.id }).containsExactly("a#1", "b#2")
        }

        @Test
        fun `should drop chunks below the minimum score`() {
            val result = Retriever.topK(floatArrayOf(1f, 0f), corpus, k = 4, minScore = 0.7)

            assertThat(result.map { it.chunk.id }).containsExactly("a#1", "b#2")
        }
    }

    @Nested
    @DisplayName("Best protocol (small-to-big)")
    inner class BestProtocol {
        @Test
        fun `should return every section of the document that holds the best chunk, in document order`() {
            val result = Retriever.bestProtocol(floatArrayOf(0.7f, 0.7f), corpus, minScore = 0.5)

            assertThat(result.map { it.chunk.id }).containsExactly("b#1", "b#2")
        }

        @Test
        fun `should return nothing when no chunk reaches the minimum score`() {
            assertThat(Retriever.bestProtocol(floatArrayOf(-1f, -1f), corpus, minScore = 0.2)).isEmpty()
        }
    }
}
