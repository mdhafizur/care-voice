package com.carevoice.alert.config

/**
 * Retrieval and generation settings of the alert guidance RAG (docs/CONTRACTS.md, section 9c).
 *
 * @property embedder `hashing` (local, deterministic), `voyage` (needs [voyageApiKey]) or `gemini` (needs
 *   [geminiApiKey]).
 * @property aiEnabled when true, an LLM phrases the guidance: [aiProvider] Claude (needs [anthropicApiKey], model
 *   [aiModel]) or Gemini (needs [geminiApiKey], model [geminiModel]); otherwise the protocol's own steps are copied.
 * @property geminiThinkingLevel `GEMINI_THINKING_LEVEL` (default `low`; `none` sends no thinking configuration).
 */
data class GuidanceConfig(
    val embedder: EmbedderKind = EmbedderKind.HASHING,
    val voyageApiKey: String? = null,
    val voyageModel: String = DEFAULT_VOYAGE_MODEL,
    val aiEnabled: Boolean = false,
    val anthropicApiKey: String? = null,
    val aiModel: String = DEFAULT_AI_MODEL,
    val aiProvider: AiProvider = AiProvider.CLAUDE,
    val geminiApiKey: String? = null,
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val geminiEmbeddingModel: String = DEFAULT_GEMINI_EMBEDDING_MODEL,
    val geminiThinkingLevel: String? = DEFAULT_GEMINI_THINKING_LEVEL,
) {
    enum class EmbedderKind { HASHING, VOYAGE, GEMINI }

    /** Which LLM writes the guidance (`AI_PROVIDER`, shared with documentation-service). */
    enum class AiProvider { CLAUDE, GEMINI }

    /** Shows API keys only as `***` (or null when unset), so the config can be logged safely. */
    override fun toString(): String =
        "GuidanceConfig(embedder=$embedder, voyageApiKey=${masked(voyageApiKey)}, voyageModel=$voyageModel, " +
            "aiEnabled=$aiEnabled, aiProvider=$aiProvider, anthropicApiKey=${masked(anthropicApiKey)}, " +
            "aiModel=$aiModel, geminiApiKey=${masked(geminiApiKey)}, geminiModel=$geminiModel, " +
            "geminiEmbeddingModel=$geminiEmbeddingModel, geminiThinkingLevel=$geminiThinkingLevel)"

    companion object {
        const val DEFAULT_VOYAGE_MODEL = "voyage-3.5-lite"
        const val DEFAULT_AI_MODEL = "claude-haiku-5-5"
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
        const val DEFAULT_GEMINI_EMBEDDING_MODEL = "gemini-embedding-001"
        const val DEFAULT_GEMINI_THINKING_LEVEL = "low"
        val GEMINI_THINKING_LEVELS = listOf("minimal", "low", "medium", "high")
    }
}

/** `***` for a set API key and null for an unset one, so a config line never shows the key itself. */
private fun masked(apiKey: String?): String? = apiKey?.let { "***" }
