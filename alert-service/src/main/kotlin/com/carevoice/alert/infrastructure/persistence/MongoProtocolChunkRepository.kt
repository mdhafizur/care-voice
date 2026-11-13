package com.carevoice.alert.infrastructure.persistence

import com.carevoice.alert.application.ProtocolChunkRepository
import com.carevoice.alert.domain.guidance.EmbeddedChunk
import com.carevoice.alert.domain.guidance.ProtocolChunk
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOneModel
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Sorts
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.bson.Document

internal object ProtocolChunkFields {
    const val COLLECTION = "protocol_chunks"
    const val CORPUS_COLLECTION = "protocol_corpora"
    const val ID = "_id"
    const val MODEL = "model"
    const val VERSION = "version"
    const val ORDER = "order"
    const val CHUNK_ID = "chunkId"
    const val DOCUMENT_ID = "documentId"
    const val DOCUMENT_TITLE = "documentTitle"
    const val SECTION = "section"
    const val TEXT = "text"
    const val VECTOR = "vector"
    const val CHUNKS = "chunks"
}

/**
 * Repository pattern (adapter): [ProtocolChunkRepository] on MongoDB. One document per chunk (`_id` =
 * `<model>|<chunkId>`, vector stored as an array of doubles) and one corpus record per model holding the current
 * version, written LAST: a reader never sees a version whose chunks are not all stored.
 *
 * Retrieval loads all chunks of a model and ranks them in memory (see [com.carevoice.alert.domain.guidance.Retriever]);
 * MongoDB Community has no vector index, and a few dozen protocol sections do not need one.
 */
class MongoProtocolChunkRepository(database: MongoDatabase) : ProtocolChunkRepository {
    private val chunks = database.getCollection(ProtocolChunkFields.COLLECTION, Document::class.java)
    private val corpora = database.getCollection(ProtocolChunkFields.CORPUS_COLLECTION, Document::class.java)

    /** The corpus version currently stored for [model], or null when nothing was stored yet. */
    override suspend fun corpusVersion(model: String): String? = MongoErrors.translate {
        corpora.find(Filters.eq(ProtocolChunkFields.ID, model)).limit(1).firstOrNull()
            ?.getString(ProtocolChunkFields.VERSION)
    }

    /**
     * Stores the new chunks, deletes the chunks of older versions, and only then writes the new version record.
     * This order means a reader never sees a version whose chunks are missing.
     */
    override suspend fun replaceCorpus(model: String, version: String, chunks: List<EmbeddedChunk>) {
        MongoErrors.translate {
            // Step 1: write the new chunks (pieces of protocol text with their embeddings).
            if (chunks.isNotEmpty()) {
                // Upserts by deterministic id: two pods ingesting the same corpus at once write identical documents.
                this.chunks.bulkWrite(
                    chunks.mapIndexed { order, embedded ->
                        val document = toDocument(model, version, order, embedded)
                        ReplaceOneModel(
                            Filters.eq(ProtocolChunkFields.ID, document.getString(ProtocolChunkFields.ID)),
                            document,
                            ReplaceOptions().upsert(true),
                        )
                    },
                )
            }
            // Step 2: delete the chunks of older versions for this model.
            this.chunks.deleteMany(
                Filters.and(
                    Filters.eq(ProtocolChunkFields.MODEL, model),
                    Filters.ne(ProtocolChunkFields.VERSION, version),
                ),
            )
            // Step 3: store the current version and the chunk count for this model.
            corpora.replaceOne(
                Filters.eq(ProtocolChunkFields.ID, model),
                Document(ProtocolChunkFields.ID, model)
                    .append(ProtocolChunkFields.VERSION, version)
                    .append(ProtocolChunkFields.CHUNKS, chunks.size),
                ReplaceOptions().upsert(true),
            )
        }
    }

    /** All chunks of [model] in their original document order. */
    override suspend fun findAll(model: String): List<EmbeddedChunk> = MongoErrors.translate {
        chunks
            .find(Filters.eq(ProtocolChunkFields.MODEL, model))
            .sort(Sorts.ascending(ProtocolChunkFields.ORDER))
            .map(::fromDocument)
            .toList()
    }

    /** One chunk as a document. The id `<model>|<chunkId>` is the same on every run, so a rerun replaces it. */
    private fun toDocument(model: String, version: String, order: Int, embedded: EmbeddedChunk): Document {
        val chunk = embedded.chunk
        return Document(ProtocolChunkFields.ID, "$model|${chunk.id}")
            .append(ProtocolChunkFields.MODEL, model)
            .append(ProtocolChunkFields.VERSION, version)
            .append(ProtocolChunkFields.ORDER, order)
            .append(ProtocolChunkFields.CHUNK_ID, chunk.id)
            .append(ProtocolChunkFields.DOCUMENT_ID, chunk.documentId)
            .append(ProtocolChunkFields.DOCUMENT_TITLE, chunk.documentTitle)
            .append(ProtocolChunkFields.SECTION, chunk.section)
            .append(ProtocolChunkFields.TEXT, chunk.text)
            .append(ProtocolChunkFields.VECTOR, embedded.vector.map { it.toDouble() })
    }

    /** Reads a stored chunk back; the vector is stored as doubles and turned back into floats. */
    private fun fromDocument(document: Document): EmbeddedChunk {
        val vector = document.getList(ProtocolChunkFields.VECTOR, Number::class.java)
        return EmbeddedChunk(
            chunk =
            ProtocolChunk(
                id = document.getString(ProtocolChunkFields.CHUNK_ID),
                documentId = document.getString(ProtocolChunkFields.DOCUMENT_ID),
                documentTitle = document.getString(ProtocolChunkFields.DOCUMENT_TITLE),
                section = document.getString(ProtocolChunkFields.SECTION),
                text = document.getString(ProtocolChunkFields.TEXT),
            ),
            vector = FloatArray(vector.size) { vector[it].toFloat() },
        )
    }
}
