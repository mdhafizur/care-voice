package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.domain.guidance.Vectors
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("HashingEmbedder")
class HashingEmbedderTest {
    private val embedder = HashingEmbedder()

    @Nested
    @DisplayName("Vectors")
    inner class VectorProperties {
        @Test
        fun `should embed the same text to the same vector every time`() {
            val text = "Recheck the blood pressure"

            assertThat(embedder.embed(text)).isEqualTo(embedder.embed(text))
        }

        @Test
        fun `should return unit-length vectors of the configured size`() {
            val vector = embedder.embed("Recheck the blood pressure after five minutes")

            assertThat(vector).hasSize(HashingEmbedder.DEFAULT_DIMENSIONS)
            assertThat(vector.sumOf { it.toDouble() * it }).isCloseTo(1.0, within(1e-5))
        }

        @Test
        fun `should return a zero vector for text without words`() {
            assertThat(embedder.embed(" ... ")).containsOnly(0f)
        }

        @Test
        fun `should embed queries and documents into the same space`() = runBlocking<Unit> {
            val query = embedder.embedQuery("high fever")
            val document = embedder.embedDocuments(listOf("high fever")).single()

            assertThat(query).isEqualTo(document)
            assertThat(embedder.model).isEqualTo("hashing-v1-512")
        }
    }

    @Nested
    @DisplayName("Similarity")
    inner class Similarity {
        @Test
        fun `should score text sharing clinical terms higher than unrelated text`() {
            val query = embedder.embed("hypertensive crisis very high blood pressure")
            val related = embedder.embed("A hypertensive crisis alert is raised when the blood pressure is very high.")
            val unrelated = embedder.embed("The resident enjoyed the music afternoon.")

            assertThat(Vectors.cosine(query, related)).isGreaterThan(Vectors.cosine(query, unrelated) + 0.3)
        }
    }

    @Nested
    @DisplayName("Tokenizer")
    inner class Tokenizer {
        @Test
        fun `should lowercase, drop stop words and short tokens, and strip a plural s`() {
            assertThat(HashingEmbedder.tokenize("Recheck the Readings of a BP, 2x"))
                .containsExactly("recheck", "reading", "bp", "2x")
        }

        @Test
        fun `should keep words ending in ss`() {
            assertThat(HashingEmbedder.tokenize("weakness")).containsExactly("weakness")
        }
    }
}
