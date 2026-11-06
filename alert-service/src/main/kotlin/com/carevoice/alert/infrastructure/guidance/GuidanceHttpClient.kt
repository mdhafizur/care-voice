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
 * adapter, so HttpTimeout must be installed. Every remote provider is wrapped in two decorators: a circuit breaker
 * ([CircuitBreakingEmbedder], [CircuitBreakingGuidanceWriter]) outside an instrumenting one ([InstrumentedEmbedder],
 * [InstrumentedGuidanceWriter]: one log line and one timer sample per real call). The breaker is outside, so a call
 * it skips is not timed as a provider call.
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
                guarded(
                    InstrumentedEmbedder(
                        VoyageEmbedder(config.voyageApiKey!!, httpClient, config.voyageModel),
                        "voyage",
                        registry,
                    ),
                    registry,
                )

            GuidanceConfig.EmbedderKind.GEMINI ->
                guarded(
                    InstrumentedEmbedder(
                        GeminiEmbedder(config.geminiApiKey!!, httpClient, apiModel = config.geminiEmbeddingModel),
                        "gemini",
                        registry,
                    ),
                    registry,
                )
        }

    private fun guarded(embedder: Embedder, registry: MeterRegistry): Embedder =
        CircuitBreakingEmbedder(embedder, GuidanceCircuitBreakers.create(GuidanceCircuitBreakers.EMBEDDER, registry))

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
        return CircuitBreakingGuidanceWriter(
            InstrumentedGuidanceWriter(writer, registry),
            GuidanceCircuitBreakers.create(GuidanceCircuitBreakers.WRITER, registry),
        )
    }
}
