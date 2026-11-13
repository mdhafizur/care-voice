package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.GuidanceGenerator
import com.carevoice.alert.application.GuidanceStep
import com.carevoice.alert.application.GuidanceWriter
import com.carevoice.alert.domain.guidance.ScoredChunk
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Configuration of [ClaudeGuidanceWriter] (env: `ANTHROPIC_API_KEY`, `AI_MODEL`). */
data class ClaudeSettings(
    val apiKey: String,
    val model: String,
    val timeout: Duration = 15.seconds,
    val baseUrl: String = "https://api.anthropic.com",
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
    }

    /** Hides the API key, so the settings can be logged safely. */
    override fun toString(): String = "ClaudeSettings(apiKey=***, model=$model, timeout=$timeout, baseUrl=$baseUrl)"
}

/**
 * Generation step of the guidance RAG: Claude turns the retrieved protocol sections into short steps for the concrete
 * alert, citing the section ids. The prompt ([GuidancePrompt]) allows ONLY the given excerpts, requires a citation per
 * step, forbids diagnoses and dosing, and defines an explicit way to decline (`NO_GUIDANCE`). The use case still
 * verifies every citation (grounding check) and falls back to the extractive writer, so a prompt that is not followed
 * cannot reach the ward lead. Temperature 0: the same alert and sources give (nearly) the same answer.
 *
 * Only protocol text and the alert message (rule, measured value, severity) are sent; never resident identifiers.
 */
class ClaudeGuidanceWriter(private val settings: ClaudeSettings, private val httpClient: HttpClient) : GuidanceWriter {
    override val generator: GuidanceGenerator = GuidanceGenerator.CLAUDE

    /** The Messages API endpoint (`POST /v1/messages`). */
    private val endpoint = settings.baseUrl.trimEnd('/') + "/v1/messages"

    /**
     * Calls the Claude Messages API and turns the answer into steps; null when the model declined.
     * Throws on a non-2xx status, so the caller can fall back to the extractive writer.
     */
    override suspend fun write(situation: String, sources: List<ScoredChunk>): List<GuidanceStep>? {
        val response =
            httpClient.post(endpoint) {
                timeout { requestTimeoutMillis = settings.timeout.inWholeMilliseconds }
                // Anthropic's API headers: `x-api-key` carries the key, `anthropic-version` picks the API version.
                header("x-api-key", settings.apiKey)
                header("anthropic-version", API_VERSION)
                setBody(TextContent(requestBody(situation, sources), ContentType.Application.Json))
            }
        val body = response.readCheckedBody("claude", settings.model, "write", failure = "Claude API")
        return parse(textOf(body))
    }

    /** JSON body for the Messages API: system prompt, one user message, temperature 0 and a token limit. */
    private fun requestBody(situation: String, sources: List<ScoredChunk>): String = buildJsonObject {
        put("model", settings.model)
        put("max_tokens", MAX_TOKENS)
        put("temperature", 0)
        put("system", SYSTEM_PROMPT)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", userPrompt(situation, sources))
            }
        }
    }.toString()

    companion object {
        /**
         * Value of the required `anthropic-version` header: the version of the Messages API format this code uses.
         * See https://docs.anthropic.com/en/api/versioning
         */
        const val API_VERSION = "2023-06-01"

        /** `max_tokens`: the most tokens (pieces of words) the answer may have; the API stops the answer there. */
        const val MAX_TOKENS = 700
        const val DECLINE = GuidancePrompt.DECLINE

        val SYSTEM_PROMPT: String = GuidancePrompt.CLAUDE_SYSTEM

        /** The user prompt shared by all providers ([GuidancePrompt.userPrompt]). */
        fun userPrompt(situation: String, sources: List<ScoredChunk>): String =
            GuidancePrompt.userPrompt(situation, sources)

        /** The text of the first `text` content block of a Messages API response (other block types are skipped). */
        fun textOf(responseBody: String): String =
            Json.parseToJsonElement(responseBody).jsonObject.getValue("content").jsonArray
                .first { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                .jsonObject.getValue("text").jsonPrimitive.content

        /** Steps from the model's answer; null when it declined (see [GuidancePrompt.parse]). */
        fun parse(answer: String): List<GuidanceStep>? = GuidancePrompt.parse(answer)
    }
}
