package com.carevoice.alert.domain.guidance

import kotlin.math.sqrt

/** Vector math for embeddings. */
object Vectors {
    /** [vector] scaled to unit length; a zero vector stays zero (it then matches nothing). */
    fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (x in vector) sum += x.toDouble() * x
        if (sum == 0.0) return vector.copyOf()
        val norm = sqrt(sum)
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    /** Cosine similarity in [-1, 1]; 0 when either vector is zero. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "vector sizes differ: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}

/**
 * Exact nearest-neighbour search: scores every chunk against the query and keeps the best [k] at or above
 * [minScore].
 *
 * Deliberately brute force: the knowledge base is a few dozen protocol sections, which this scores in microseconds,
 * and exact search has no recall loss. An approximate index (pgvector HNSW, a vector database) pays off only at many
 * thousands of chunks; the retrieval port lets the service switch without touching the use case.
 */
object Retriever {
    fun topK(query: FloatArray, corpus: List<EmbeddedChunk>, k: Int, minScore: Double): List<ScoredChunk> {
        require(k > 0) { "k must be positive" }
        return corpus
            .asSequence()
            .map { ScoredChunk(it.chunk, Vectors.cosine(query, it.vector)) }
            .filter { it.score >= minScore }
            .sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.chunk.id })
            .take(k)
            .toList()
    }

    /**
     * Small-to-big retrieval: the best-scoring chunk decides WHICH protocol matches, then the whole protocol (all its
     * sections, in document order, each with its own score) is returned. Chunk-level search finds the right document
     * precisely; the answer still needs every section of it ("Immediate steps" AND "When to escalate"), which a plain
     * top-k would often cut off. Empty when no chunk reaches [minScore].
     */
    fun bestProtocol(query: FloatArray, corpus: List<EmbeddedChunk>, minScore: Double): List<ScoredChunk> {
        val scored = corpus.map { ScoredChunk(it.chunk, Vectors.cosine(query, it.vector)) }
        val best = scored.filter { it.score >= minScore }.maxWithOrNull(
            compareBy<ScoredChunk> { it.score }.thenByDescending { it.chunk.id },
        ) ?: return emptyList()
        return scored.filter { it.chunk.documentId == best.chunk.documentId }
    }
}
