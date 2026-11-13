package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/** Logger of its own for the provider HTTP answers, so their DEBUG lines can be turned on separately. */
private val providerHttpLog = LoggerFactory.getLogger("com.carevoice.alert.infrastructure.guidance.ProviderHttp")

/**
 * What a provider error code looks like: 1 to 64 letters, digits, `_` or `.` (e.g. Gemini `RESOURCE_EXHAUSTED`,
 * Anthropic `overloaded_error`). Anything else is not logged.
 */
private val API_ERROR_CODE = Regex("^[A-Za-z0-9_.]{1,64}$")

/**
 * DEBUG: one line per HTTP answer of a guidance provider (Claude, Gemini, Voyage): status and, on failure, the
 * provider's machine-readable error code (Gemini `error.status`, Anthropic `error.type`). Never the body, the error
 * message or the prompt: they can echo protocol text or the request.
 */
internal fun logProviderResponse(provider: String, model: String, operation: String, status: Int, body: String) {
    // Step 1: do nothing unless debug logging is on, so the body is not parsed for nothing.
    if (!providerHttpLog.isDebugEnabled) return
    // Step 2: for an error answer, read the provider's error code from the body. Keep it only
    // when it looks like a short code, so no free text from the body reaches the logs.
    val apiError =
        if (status in 200..299) {
            null
        } else {
            runCatching {
                val error = Json.parseToJsonElement(body).jsonObject["error"] as? JsonObject
                ((error?.get("status") ?: error?.get("type")) as? JsonPrimitive)?.contentOrNull
            }.getOrNull()?.takeIf { API_ERROR_CODE.matches(it) }
        }
    // Step 3: log the status and the error code, never the body itself.
    providerHttpLog.event(
        Level.DEBUG,
        LogType.EXTERNAL_REQUEST,
        "Guidance provider responded",
        "provider" to provider,
        "model" to model,
        "operation" to operation,
        "httpStatus" to status,
        "apiError" to apiError,
    )
}

/**
 * Reads the body of a guidance provider's HTTP answer, logs the answer with [logProviderResponse], and throws an
 * [IllegalStateException] ("<failure> failed with HTTP <status>") when the status is not a success (2xx).
 * Every remote provider (Claude, Gemini, Voyage) checks its answer this way, so the caller can fall back.
 */
internal suspend fun HttpResponse.readCheckedBody(
    provider: String,
    model: String,
    operation: String,
    failure: String,
): String {
    val body = bodyAsText()
    logProviderResponse(provider, model, operation, status.value, body)
    check(status.isSuccess()) { "$failure failed with HTTP ${status.value}" }
    return body
}
