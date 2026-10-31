package com.carevoice.alert.domain.guidance

/**
 * One retrievable unit of the protocol knowledge base: a `##` section of a protocol document.
 *
 * @property id stable citation id, `<documentId>#<n>` (e.g. `hypertensive-crisis#2`); answers cite it, the API returns
 *   it, and a reviewer can find the exact text it refers to.
 * @property text what is embedded and shown as the excerpt: the section body, prefixed with the document title and
 *   section name so a section like "Immediate steps" still carries its context ("Hypertensive crisis").
 */
data class ProtocolChunk(
    val id: String,
    val documentId: String,
    val documentTitle: String,
    val section: String,
    val text: String,
)

/** A chunk with its embedding vector (unit length). Not a data class: arrays have identity equality. */
class EmbeddedChunk(val chunk: ProtocolChunk, val vector: FloatArray)

/** A retrieved chunk and its cosine similarity to the query (higher is more relevant). */
data class ScoredChunk(val chunk: ProtocolChunk, val score: Double)
