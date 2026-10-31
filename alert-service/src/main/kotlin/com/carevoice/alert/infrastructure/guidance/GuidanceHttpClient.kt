package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.Embedder
import com.carevoice.alert.application.GuidanceWriter
import com.carevoice.alert.config.GuidanceConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.micrometer.core.instrument.MeterRegistry

/**
 * Factory pattern: the HTTP client, the embedder and the LLM writer for the guidance providers (Voyage, Claude,
 * Gemini). One shared client per process (its connection pool is reused); per-request timeouts are set by each
 * adapter, so HttpTimeout must be installed. Every remote provider is wrapped in an instrumenting decorator
 * ([InstrumentedEmbedder], [InstrumentedGuidanceWriter]): one log line and one timer sample per call.
 */
object GuidanceHttpClient {
    fun create(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 20_000
        }
        expectSuccess = false
    }

    /** The embedder selected by `GUIDANCE_EMBEDDER`; the local hashing embedder is not instrumented. */
    fun embedder(config: GuidanceConfig, httpClient: HttpClient, registry: MeterRegistry): Embedder =
        when (config.embedder) {
            GuidanceConfig.EmbedderKind.HASHING -> HashingEmbedder()

            GuidanceConfig.EmbedderKind.VOYAGE ->
                InstrumentedEmbedder(
                    VoyageEmbedder(config.voyageApiKey!!, httpClient, config.voyageModel),
                    "voyage",
                    registry,
                )

            GuidanceConfig.EmbedderKind.GEMINI ->
                InstrumentedEmbedder(
                    GeminiEmbedder(config.geminiApiKey!!, httpClient, apiModel = config.geminiEmbeddingModel),
                    "gemini",
                    registry,
                )
        }

    /** The LLM guidance writer selected by `AI_PROVIDER`, or null when `GUIDANCE_AI_ENABLED` is false. */
    fun writer(config: GuidanceConfig, httpClient: HttpClient, registry: MeterRegistry): GuidanceWriter? {
        if (!config.aiEnabled) return null
        val writer =
            when (config.aiProvider) {
                GuidanceConfig.AiProvider.CLAUDE ->
                    ClaudeGuidanceWriter(ClaudeSettings(config.anthropicApiKey!!, config.aiModel), httpClient)

                GuidanceConfig.AiProvider.GEMINI ->
                    GeminiGuidanceWriter(
                        GeminiSettings(
                            apiKey = config.geminiApiKey!!,
                            model = config.geminiModel,
                            thinkingLevel = config.geminiThinkingLevel,
                        ),
                        httpClient,
                    )
            }
        return InstrumentedGuidanceWriter(writer, registry)
    }
}
