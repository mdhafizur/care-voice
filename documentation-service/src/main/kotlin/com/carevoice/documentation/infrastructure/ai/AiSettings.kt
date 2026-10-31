package com.carevoice.documentation.infrastructure.ai

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration of one AI extraction adapter (env: `AI_PROVIDER` picks Claude or Gemini; `ANTHROPIC_API_KEY` +
 * `AI_MODEL` or `GEMINI_API_KEY` + `GEMINI_MODEL`; see docs/CONTRACTS.md section 4).
 *
 * @property timeout per HTTP attempt. Short on purpose: the nurse is waiting for the response, and the rules result
 *   is a perfectly good answer without AI.
 * @property thinkingLevel Gemini only: `thinkingConfig.thinkingLevel`, or null to send none (needed for Gemini 2.5
 *   models, which reject it). Ignored by Claude.
 */
data class AiSettings(
    val apiKey: String,
    val model: String,
    val timeout: Duration = 3.seconds,
    val baseUrl: String = "https://api.anthropic.com",
    val thinkingLevel: String? = null,
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(timeout.isPositive()) { "timeout must be positive" }
    }

    /** Never prints the API key, so the settings object is safe to log or show in a failed assertion. */
    override fun toString(): String =
        "AiSettings(apiKey=***, model=$model, timeout=$timeout, baseUrl=$baseUrl, thinkingLevel=$thinkingLevel)"
}
