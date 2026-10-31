package com.carevoice.alert.application

import com.carevoice.alert.infrastructure.guidance.ClasspathProtocolSource
import com.carevoice.alert.infrastructure.guidance.HashingEmbedder
import com.carevoice.alert.support.InMemoryProtocolChunks
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("IngestProtocols")
class IngestProtocolsTest {
    private val embedder = HashingEmbedder()

    /** Counts embedding calls, so the test can prove an unchanged corpus is not embedded again. */
    private class CountingEmbedder(private val delegate: Embedder) : Embedder by delegate {
        var documentsEmbedded = 0
            private set

        override suspend fun embedDocuments(texts: List<String>): List<FloatArray> {
            documentsEmbedded += texts.size
            return delegate.embedDocuments(texts)
        }
    }

    private fun source(vararg documents: Pair<String, String>) =
        ProtocolSource { documents.map { (id, markdown) -> ProtocolDocument(id, markdown) } }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should chunk, embed and store every section of every shipped protocol`() = runBlocking<Unit> {
            val repository = InMemoryProtocolChunks()

            val result = IngestProtocols(ClasspathProtocolSource(), embedder, repository).execute()

            assertThat(result).isEqualTo(IngestResult(chunks = 28, changed = true))
            assertThat(repository.findAll(embedder.model).map { it.chunk.documentId }.distinct()).containsExactly(
                "hypertensive-crisis",
                "hypotension",
                "tachycardia",
                "bradycardia",
                "high-fever",
                "hypothermia",
                "unconfirmed-values",
            )
        }

        @Test
        fun `should not embed again when the corpus is unchanged`() = runBlocking<Unit> {
            val repository = InMemoryProtocolChunks()
            val counting = CountingEmbedder(embedder)
            val ingest = IngestProtocols(ClasspathProtocolSource(), counting, repository)
            ingest.execute()

            val second = ingest.execute()

            assertThat(second.changed).isFalse()
            assertThat(counting.documentsEmbedded).isEqualTo(28)
            assertThat(repository.replaceCalls).isEqualTo(1)
        }

        @Test
        fun `should rebuild the corpus when a protocol changes`() = runBlocking<Unit> {
            val repository = InMemoryProtocolChunks()
            IngestProtocols(source("p" to "# P\n\n## A\nOld text."), embedder, repository).execute()

            val result = IngestProtocols(source("p" to "# P\n\n## A\nNew text."), embedder, repository).execute()

            assertThat(result.changed).isTrue()
            assertThat(repository.findAll(embedder.model).single().chunk.text).isEqualTo("P. A.\nNew text.")
        }

        @Test
        fun `should give the same texts a different version under another embedding model`() {
            val texts = listOf("a", "b")

            val version = IngestProtocols.versionOf("model-1", texts)

            assertThat(version).isNotEqualTo(IngestProtocols.versionOf("model-2", texts))
            assertThat(version).isEqualTo(IngestProtocols.versionOf("model-1", texts))
        }
    }
}
