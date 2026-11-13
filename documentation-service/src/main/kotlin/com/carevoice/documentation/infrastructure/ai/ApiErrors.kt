package com.carevoice.documentation.infrastructure.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The machine-readable error code of a provider's error response, for logs: Gemini's `error.status`
 * (`RESOURCE_EXHAUSTED`, `INVALID_ARGUMENT`, `PERMISSION_DENIED`, ...) or Anthropic's `error.type`
 * (`overloaded_error`, `authentication_error`, ...). Only the code, never `error.message`: a message may echo parts of
 * the request, and the request contains the transcript.
 */
internal object ApiErrors {
    private val json: Json = Json.Default

    /** An error code we log: 1 to 64 letters, digits, `_` or `.`. Anything else is not logged. */
    private val CODE = Regex("^[A-Za-z0-9_.]{1,64}$")

    /** Returns the error code from an error response body, or null when there is none or it looks unsafe to log. */
    fun code(body: String): String? = runCatching {
        val error = json.parseToJsonElement(body).jsonObject["error"] as? JsonObject ?: return null
        val code = error.primitiveText("status") ?: error.primitiveText("type")
        code?.takeIf { CODE.matches(it) }
    }.getOrNull()
}
