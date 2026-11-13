package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.Embedder
import com.carevoice.alert.domain.guidance.Vectors
import java.util.Locale
import kotlin.math.ln

/**
 * Local embedder (default): feature hashing of word unigrams and bigrams into a fixed-size vector, log-scaled term
 * frequencies, unit length. Lexical, not semantic: it matches shared words ("hypertensive crisis", "pulse"), not
 * synonyms.
 *
 * Why it exists: deterministic (tests and the retrieval evaluation run without network or API keys), free, and no
 * health-related text leaves the service. For a curated protocol corpus whose wording the queries share, lexical
 * matching is enough (the evaluation test proves it for every alert rule). Switch to [VoyageEmbedder] for semantic
 * matching of free-text questions.
 */
class HashingEmbedder(private val dimensions: Int = DEFAULT_DIMENSIONS) : Embedder {
    init {
        require(dimensions in 64..4096) { "dimensions must be in 64..4096" }
    }

    override val model: String = "hashing-v1-$dimensions"

    /** Unrelated text scores below ~0.1 against the protocols, the matching protocol above ~0.4 (evaluation set). */
    override val minScore: Double = 0.2

    /** Documents and queries are embedded the same way; this embedder has no asymmetric mode. */
    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> = texts.map(::embed)

    /** Same as a document: the hashing embedder treats queries and documents alike. */
    override suspend fun embedQuery(text: String): FloatArray = embed(text)

    /**
     * Hashes each word and each pair of neighbouring words to a position in the vector.
     * One hash bit picks the sign (+ or -), so collisions tend to cancel out instead of always adding up.
     * Repeated words count as 1 + ln(count), so they do not dominate. The result has length 1.
     */
    fun embed(text: String): FloatArray {
        val tokens = tokenize(text)
        val features = tokens + tokens.zipWithNext { a, b -> "$a $b" }
        val vector = FloatArray(dimensions)
        for ((feature, count) in features.groupingBy { it }.eachCount()) {
            val hash = fnv1a(feature)
            val index = (hash ushr 1) % dimensions
            val sign = if ((hash and 1) == 0) 1f else -1f
            vector[index] += sign * (1f + ln(count.toFloat()))
        }
        return Vectors.normalize(vector)
    }

    companion object {
        const val DEFAULT_DIMENSIONS = 512

        private val STOP_WORDS =
            (
                "a an the and or of to in on for with is are be if it as at by this that after before until any all " +
                    "can not do does has have was were from their them they when then than also both every each your " +
                    "you our its into out up down"
                ).split(' ').toSet()

        /** Lowercase alphanumeric words, stop words removed, a plural "s" stripped ("readings" -> "reading"). */
        fun tokenize(text: String): List<String> = text
            .lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .map { if (it.length > 4 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }

        /** 32-bit FNV-1a: stable across JVMs and runs (String.hashCode would also be stable, but is weak). */
        fun fnv1a(text: String): Int {
            var hash = -0x7ee3623b // 0x811C9DC5
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toInt() and 0xff)
                hash *= 16_777_619
            }
            return hash
        }
    }
}
