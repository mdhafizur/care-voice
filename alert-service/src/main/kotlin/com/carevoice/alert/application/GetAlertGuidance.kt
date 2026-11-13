package com.carevoice.alert.application

import com.carevoice.alert.domain.WardScope
import com.carevoice.alert.domain.guidance.GuidanceQuery
import com.carevoice.alert.domain.guidance.RetrievalQuery
import com.carevoice.alert.domain.guidance.Retriever
import com.carevoice.alert.domain.guidance.ScoredChunk
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.coroutines.cancellation.CancellationException

/**
 * Use case: protocol guidance for one alert of the caller's ward (retrieval-augmented generation, ADR-0023).
 *
 * 1. Retrieve: one query per aspect of the alert (clinical situation; "check an unconfirmed value"), each selecting the
 *    best-matching protocol (small-to-big: all sections of that protocol).
 * 2. Generate: the configured [writer] (Claude or Gemini) phrases steps from those sections only, citing section ids.
 * 3. Ground: every step must cite at least one retrieved section and nothing else; an answer that fails the check, a
 *    declined answer or any writer error falls back to [extractive], which copies the protocol's own steps. The
 *    caller always gets either cited guidance or an explicit NO_MATCHING_PROTOCOL, never an unsourced text.
 *
 * Advisory only: nothing here changes the alert or any data.
 *
 * @param chunks null when no knowledge base is configured; every alert then gets NO_MATCHING_PROTOCOL.
 * @param writer null when generation is disabled; the extractive writer is used.
 */
class GetAlertGuidance(
    private val alerts: AlertRepository,
    private val embedder: Embedder,
    private val chunks: ProtocolChunkRepository?,
    private val writer: GuidanceWriter?,
    private val extractive: GuidanceWriter = ExtractiveGuidanceWriter(),
) {
    /**
     * Builds guidance for one alert: finds the best protocol per query, then writes cited steps from it.
     * Answers NO_MATCHING_PROTOCOL when no section scores at least the embedder's minimum score.
     */
    suspend fun execute(scope: WardScope, alertId: String): AlertGuidance {
        val alert = alerts.findById(scope, alertId) ?: throw AlertServiceException.AlertNotFound(alertId)
        val corpus = chunks?.findAll(embedder.model).orEmpty()

        // Review first: for an unconfirmed value, "measure it yourself" comes before the clinical response.
        val sources =
            GuidanceQuery
                .forAlert(alert)
                .sortedBy { if (it.aspect == RetrievalQuery.Aspect.REVIEW) 0 else 1 }
                .flatMap { query ->
                    Retriever.bestProtocol(embed(query.text), corpus, embedder.minScore).also { hits ->
                        log.event(
                            Level.DEBUG,
                            LogType.BUSINESS,
                            "Protocol retrieved",
                            "alertId" to alertId,
                            "aspect" to query.aspect.name,
                            "protocol" to hits.firstOrNull()?.chunk?.documentId,
                            "topScore" to hits.maxOfOrNull { it.score },
                            "sections" to hits.size,
                            "minScore" to embedder.minScore,
                            "corpusChunks" to corpus.size,
                        )
                    }
                }
                .distinctBy { it.chunk.id }
        if (sources.isEmpty()) {
            log.event(
                Level.INFO,
                LogType.BUSINESS,
                "Guidance served",
                "alertId" to alertId,
                "status" to GuidanceStatus.NO_MATCHING_PROTOCOL.name,
                "corpusChunks" to corpus.size,
            )
            return AlertGuidance(alert, GuidanceStatus.NO_MATCHING_PROTOCOL, null, emptyList(), emptyList())
        }

        val situation = "${alert.message} Severity: ${alert.severity.name}."
        val generated = writer?.let { generate(it, situation, sources) }
        val (generator, steps) =
            generated ?: (extractive.generator to extractive.write(situation, sources).orEmpty())
        log.event(
            Level.INFO,
            LogType.BUSINESS,
            "Guidance served",
            "alertId" to alertId,
            "status" to GuidanceStatus.FOUND.name,
            "generatedBy" to generator.name,
            "steps" to steps.size,
            "sources" to sources.size,
            "protocols" to sources.map { it.chunk.documentId }.distinct().joinToString(","),
        )
        return AlertGuidance(alert, GuidanceStatus.FOUND, generator, steps, sources)
    }

    /** Embeds a query; a failing embedding service becomes DependencyUnavailable (503). Cancellation is passed on. */
    private suspend fun embed(text: String): FloatArray = try {
        embedder.embedQuery(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw AlertServiceException.DependencyUnavailable("embedding service", e)
    }

    /**
     * Asks [writer] for steps and keeps them only when they are grounded (they cite retrieved sections only).
     * Returns null when the writer throws, declines or is not grounded, so the caller uses the extractive writer.
     */
    private suspend fun generate(
        writer: GuidanceWriter,
        situation: String,
        sources: List<ScoredChunk>,
    ): Pair<GuidanceGenerator, List<GuidanceStep>>? {
        val steps =
            try {
                writer.write(situation, sources)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.event(
                    Level.WARN,
                    LogType.EXTERNAL_REQUEST,
                    "Guidance writer failed; falling back to extractive guidance",
                    "writer" to writer.generator.name,
                    cause = e,
                )
                null
            }
        if (steps == null) return null
        if (!isGrounded(steps, sources)) {
            log.event(
                Level.WARN,
                LogType.BUSINESS,
                "Guidance writer returned ungrounded steps; falling back to extractive",
                "writer" to writer.generator.name,
            )
            return null
        }
        return writer.generator to steps
    }

    companion object {
        const val MAX_STEPS = 8
        const val MAX_STEP_LENGTH = 400
        private val log = LoggerFactory.getLogger(GetAlertGuidance::class.java)

        /** Every step cites only retrieved sections, at least one each; bounded count and length. */
        fun isGrounded(steps: List<GuidanceStep>, sources: List<ScoredChunk>): Boolean {
            val ids = sources.map { it.chunk.id }.toSet()
            return steps.isNotEmpty() &&
                steps.size <= MAX_STEPS &&
                steps.all { step ->
                    step.text.isNotBlank() &&
                        step.text.length <= MAX_STEP_LENGTH &&
                        step.citations.isNotEmpty() &&
                        step.citations.all { it in ids }
                }
        }
    }
}

/**
 * The generation-free writer: copies the bullet points of the actionable sections ("Immediate steps", "When to
 * escalate") of the retrieved protocols, each citing its section. Always grounded by construction; used when no LLM is
 * configured and as the fallback when the LLM's answer fails the grounding check.
 */
class ExtractiveGuidanceWriter : GuidanceWriter {
    override val generator: GuidanceGenerator = GuidanceGenerator.EXTRACTIVE

    /** Copies the bullets of the action sections (all sections when none match), at most MAX_STEPS steps. */
    override suspend fun write(situation: String, sources: List<ScoredChunk>): List<GuidanceStep> {
        val actionable = sources.filter { it.chunk.section in ACTION_SECTIONS }.ifEmpty { sources }
        return actionable
            .flatMap { source -> bullets(source.chunk.text).map { GuidanceStep(it, listOf(source.chunk.id)) } }
            .take(GetAlertGuidance.MAX_STEPS)
    }

    /** `- item` lines, with indented continuation lines joined; the first sentence when a section has no bullets. */
    private fun bullets(text: String): List<String> {
        val items = mutableListOf<StringBuilder>()
        for (line in text.lines().drop(1)) {
            when {
                line.startsWith("- ") -> items += StringBuilder(line.removePrefix("- ").trim())
                line.startsWith("  ") && items.isNotEmpty() -> items.last().append(' ').append(line.trim())
            }
        }
        if (items.isNotEmpty()) return items.map { it.toString().take(GetAlertGuidance.MAX_STEP_LENGTH) }
        val body = text.lines().drop(1).joinToString(" ").trim()
        return listOf(body.substringBefore(". ").trimEnd('.') + ".").filter { it.length > 1 }
    }

    companion object {
        val ACTION_SECTIONS = setOf("Immediate steps", "When to escalate")
    }
}
