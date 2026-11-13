package com.carevoice.alert.application

import com.carevoice.alert.domain.guidance.EmbeddedChunk
import com.carevoice.alert.domain.guidance.ProtocolChunker
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.MessageDigest

/** What [IngestProtocols] did: [changed] is false when the stored corpus was already up to date. */
data class IngestResult(val chunks: Int, val changed: Boolean)

/**
 * Use case: (re)builds the protocol knowledge base: chunk every document, embed the chunks, store them.
 *
 * Idempotent and cheap to repeat: the corpus version is a hash of the embedding model and every chunk text, so a
 * restart with unchanged documents embeds nothing, and two pods starting at once write the same documents (the
 * repository upserts by chunk id). Runs at every startup, so editing a protocol file and deploying is all it takes.
 */
class IngestProtocols(
    private val source: ProtocolSource,
    private val embedder: Embedder,
    private val repository: ProtocolChunkRepository,
) {
    private val log = LoggerFactory.getLogger(IngestProtocols::class.java)

    /**
     * Chunks all documents and compares the corpus version with the stored one.
     * Only when they differ does it embed the chunks and replace the stored corpus.
     */
    suspend fun execute(): IngestResult {
        // Step 1: read the protocol documents and cut them into chunks (small pieces of text).
        val documents = source.documents()
        val chunks = documents.flatMap { ProtocolChunker.chunk(it.id, it.markdown) }
        // Step 2: the version is a SHA-256 hash of the model name and all chunks. Compare it with the stored one.
        // U+0001 (a control character that never occurs in a chunk id) separates each id from its text.
        val version = versionOf(embedder.model, chunks.map { it.id + "\u0001" + it.text })
        val stored = repository.corpusVersion(embedder.model)
        log.event(
            Level.DEBUG,
            LogType.BUSINESS,
            "Protocol corpus compared",
            "documents" to documents.size,
            "chunks" to chunks.size,
            "embeddingModel" to embedder.model,
            // Only the first 12 hex characters of each hash are logged, to keep the line short.
            "version" to version.take(12),
            "storedVersion" to stored?.take(12),
            "reembed" to (stored != version),
        )
        // Step 3: nothing changed, so skip the slow embedding work.
        if (stored == version) return IngestResult(chunks.size, changed = false)

        // Step 4: turn each chunk into a vector (a list of numbers for similarity search), in batches.
        val vectors = chunks.chunked(BATCH_SIZE).flatMap { batch -> embedder.embedDocuments(batch.map { it.text }) }
        check(vectors.size == chunks.size) { "embedder returned ${vectors.size} vectors for ${chunks.size} chunks" }
        // Step 5: replace the whole stored corpus with the new chunks and their vectors.
        repository.replaceCorpus(embedder.model, version, chunks.zip(vectors) { chunk, v -> EmbeddedChunk(chunk, v) })
        return IngestResult(chunks.size, changed = true)
    }

    companion object {
        /** Texts per embedding request (provider APIs limit batch sizes). */
        const val BATCH_SIZE = 32

        /**
         * SHA-256 hash (a fixed-size fingerprint) of the model name and all texts, as hex.
         * A zero byte goes before each text, so `["ab"]` and `["a", "b"]` give different hashes.
         */
        fun versionOf(model: String, texts: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(model.toByteArray())
            texts.forEach {
                digest.update(0.toByte())
                digest.update(it.toByteArray())
            }
            // "%02x" writes each byte as exactly two lowercase hex digits (e.g. 05, a3).
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
