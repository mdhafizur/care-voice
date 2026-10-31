package com.carevoice.documentation.infrastructure.ai

import com.carevoice.documentation.resilience.CircuitBreakerSettings
import com.carevoice.documentation.resilience.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Vital-sign extraction through the Anthropic Messages API (`POST /v1/messages`), selected with `AI_PROVIDER=claude`
 * (the default). Resilience, metrics and the failure policy live in [HttpAiVitalExtractor]; this class is the wire
 * format only. The API key is sent in `x-api-key`.
 */
class ClaudeVitalExtractor(
    settings: AiSettings,
    httpClient: HttpClient,
    meterRegistry: MeterRegistry,
    circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(),
    retryPolicy: RetryPolicy = HttpAiVitalExtractor.DEFAULT_RETRY_POLICY,
) : HttpAiVitalExtractor(
    settings,
    httpClient,
    meterRegistry,
    CIRCUIT_BREAKER_NAME,
    circuitBreakerSettings,
    retryPolicy,
) {
    override val endpoint: String = settings.baseUrl.trimEnd('/') + MESSAGES_PATH

    override fun HttpRequestBuilder.authenticate() {
        header(API_KEY_HEADER, settings.apiKey)
        header(VERSION_HEADER, API_VERSION)
    }

    /** Deterministic output (temperature 0), small max_tokens: the answer is a short JSON array. */
    override fun requestBody(normalized: String): String = buildJsonObject {
        put("model", settings.model)
        put("max_tokens", MAX_TOKENS)
        put("temperature", 0)
        put("system", SYSTEM_PROMPT)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", VitalExtractionPrompt.userMessage(normalized))
            }
        }
    }.toString()

    override fun answerText(responseBody: String): String? = ClaudeResponseParser.textOf(responseBody)

    companion object {
        const val MESSAGES_PATH = "/v1/messages"
        const val API_KEY_HEADER = "x-api-key"
        const val VERSION_HEADER = "anthropic-version"
        const val API_VERSION = "2023-06-01"
        const val CIRCUIT_BREAKER_NAME = "anthropic"
        const val DEFAULT_MODEL = "claude-haiku-5-5"

        const val FALLBACK_TOTAL_METRIC = HttpAiVitalExtractor.FALLBACK_TOTAL_METRIC
        const val REQUEST_SECONDS_METRIC = HttpAiVitalExtractor.REQUEST_SECONDS_METRIC

        private const val MAX_TOKENS = 512

        /** Claude follows the output format from the instructions alone. */
        val SYSTEM_PROMPT: String = VitalExtractionPrompt.SYSTEM
    }
}
