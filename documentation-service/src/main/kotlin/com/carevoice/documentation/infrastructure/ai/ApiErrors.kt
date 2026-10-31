package com.carevoice.documentation.infrastructure.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The machine-readable error code of a provider's error response, for logs: Gemini's `error.status`
 * (`RESOURCE_EXHAUSTED`, `INVALID_ARGUMENT`, `PERMISSION_DENIED`, ...) or Anthropic's `error.type`
 * (`overloaded_error`, `authentication_error`, ...). Only the code, never `error.message`: a message may echo parts of
 * the request, and the request contains the transcript.
 */
internal object ApiErrors {
    private val json: Json = Json.Default
    private val CODE = Regex("^[A-Za-z0-9_.]{1,64}$")

    fun code(body: String): String? = runCatching {
        val error = json.parseToJsonElement(body).jsonObject["error"] as? JsonObject ?: return null
        val code =
            (error["status"] as? JsonPrimitive)?.contentOrNull ?: (error["type"] as? JsonPrimitive)?.contentOrNull
        code?.takeIf { CODE.matches(it) }
    }.getOrNull()
}
