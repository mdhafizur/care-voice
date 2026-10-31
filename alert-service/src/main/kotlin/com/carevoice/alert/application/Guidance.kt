package com.carevoice.alert.application

import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.guidance.EmbeddedChunk
import com.carevoice.alert.domain.guidance.ScoredChunk

/**
 * Adapter pattern (port): turns text into embedding vectors of unit length. Implementations: a local hashing embedder
 * (default; no network, deterministic) and Voyage AI. Documents and queries are separate calls because some models
 * embed them differently (asymmetric retrieval).
 */
interface Embedder {
    /** Identifies the vector space; chunks embedded by one model are never compared with another model's query. */
    val model: String

    /** Minimum cosine similarity for a chunk to count as relevant. Model-specific: score distributions differ. */
    val minScore: Double

    suspend fun embedDocuments(texts: List<String>): List<FloatArray>

    suspend fun embedQuery(text: String): FloatArray
}

/** One protocol document of the knowledge base, as markdown. */
data class ProtocolDocument(val id: String, val markdown: String)

/** Port: where protocol documents come from (the classpath today; a document store or CMS later). */
fun interface ProtocolSource {
    fun documents(): List<ProtocolDocument>
}

/**
 * Repository pattern (port) for the embedded protocol chunks. A corpus is stored per embedding [model]; its
 * `version` is a hash of the model and all chunk texts, so unchanged documents are never re-embedded.
 */
interface ProtocolChunkRepository {
    suspend fun corpusVersion(model: String): String?

    /** Stores [chunks] as the corpus of [model] in [version] and removes chunks of any other version of that model. */
    suspend fun replaceCorpus(model: String, version: String, chunks: List<EmbeddedChunk>)

    suspend fun findAll(model: String): List<EmbeddedChunk>
}

/** One step of guidance, with the ids of the protocol sections it is based on. */
data class GuidanceStep(val text: String, val citations: List<String>)

enum class GuidanceGenerator { CLAUDE, GEMINI, EXTRACTIVE }

enum class GuidanceStatus { FOUND, NO_MATCHING_PROTOCOL }

/**
 * Strategy pattern (port): writes guidance steps from the retrieved protocol sections. [ExtractiveGuidanceWriter]
 * copies the protocol's own steps; the Claude adapter phrases them for the concrete alert.
 */
interface GuidanceWriter {
    val generator: GuidanceGenerator

    /** The steps, or null when the writer declines (the sources do not cover the situation). */
    suspend fun write(situation: String, sources: List<ScoredChunk>): List<GuidanceStep>?
}

/** The result of [GetAlertGuidance]. [sources] are the retrieved sections the steps may cite. */
data class AlertGuidance(
    val alert: Alert,
    val status: GuidanceStatus,
    val generatedBy: GuidanceGenerator?,
    val steps: List<GuidanceStep>,
    val sources: List<ScoredChunk>,
)
