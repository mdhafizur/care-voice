package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.Embedder
import com.carevoice.alert.application.GuidanceGenerator
import com.carevoice.alert.application.GuidanceStep
import com.carevoice.alert.application.GuidanceWriter
import com.carevoice.alert.domain.guidance.ScoredChunk
import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * Times and logs one call to a guidance provider (Claude, Gemini, Voyage), the way documentation-service records each
 * AI extraction: `carevoice_guidance_request_seconds{provider, operation, result}` (percentile histogram) and one
 * `externalrequest` log line with provider, operation, result, duration and item count. Never the prompt, the
 * protocol text or the answer. Failures are rethrown unchanged; the caller decides the fallback and logs the cause.
 */
internal class ProviderCalls(private val provider: String, private val registry: MeterRegistry) {
    /**
     * Runs [call], then records one timer sample and one log line with the result.
     * [resultOf] names the result of a successful call (e.g. "declined"); any exception counts as "error" and is
     * rethrown. Cancellation is passed on at once and not recorded.
     */
    suspend fun <T> record(operation: String, items: Int, call: suspend () -> T, resultOf: (T) -> String = { OK }): T {
        // Step 1: run the call and time it. A failure is kept as a value for now, so it is still
        // measured and logged.
        val mark = TimeSource.Monotonic.markNow()
        val outcome =
            try {
                Result.success(call())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        // Step 2: turn the outcome into a result label and record the time in a timer metric with it.
        val elapsed = mark.elapsedNow()
        val result = outcome.fold(resultOf) { ERROR }
        Timer
            .builder(REQUEST_SECONDS_METRIC)
            .description("Duration of one call to a guidance provider")
            .tags("provider", provider, "operation", operation, "result", result)
            .publishPercentileHistogram()
            .register(registry)
            .record(elapsed.toJavaDuration())
        // Step 3: log one line per call, at WARN when it failed.
        log.event(
            if (outcome.isFailure) Level.WARN else Level.INFO,
            LogType.EXTERNAL_REQUEST,
            "Guidance provider call finished",
            "provider" to provider,
            "operation" to operation,
            "result" to result,
            "items" to items,
            "durationMs" to elapsed.inWholeMilliseconds,
            "error" to outcome.exceptionOrNull()?.javaClass?.simpleName,
        )
        // Step 4: return the value, or throw the saved error again.
        return outcome.getOrThrow()
    }

    companion object {
        /** `carevoice_guidance_request_seconds{provider, operation, result}` */
        const val REQUEST_SECONDS_METRIC = "carevoice.guidance.request"
        const val OK = "ok"
        const val ERROR = "error"
        const val DECLINED = "declined"

        private val log = LoggerFactory.getLogger(ProviderCalls::class.java)
    }
}

/** Decorator: [ProviderCalls] around any LLM [GuidanceWriter]. */
class InstrumentedGuidanceWriter(private val delegate: GuidanceWriter, registry: MeterRegistry) : GuidanceWriter {
    override val generator: GuidanceGenerator = delegate.generator

    private val calls = ProviderCalls(delegate.generator.name.lowercase(), registry)

    /** Records the write call; a null answer (the model declined) is recorded as "declined", not as "ok". */
    override suspend fun write(situation: String, sources: List<ScoredChunk>): List<GuidanceStep>? = calls.record(
        operation = "write",
        items = sources.size,
        call = { delegate.write(situation, sources) },
        resultOf = { steps -> if (steps == null) ProviderCalls.DECLINED else ProviderCalls.OK },
    )
}

/** Decorator: [ProviderCalls] around a remote [Embedder] (the local hashing embedder needs no instrumentation). */
class InstrumentedEmbedder(private val delegate: Embedder, provider: String, registry: MeterRegistry) : Embedder {
    override val model: String = delegate.model
    override val minScore: Double = delegate.minScore

    private val calls = ProviderCalls(provider, registry)

    /** Records one "embed_documents" call with the number of texts as the item count. */
    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
        calls.record("embed_documents", texts.size, { delegate.embedDocuments(texts) })

    /** Records one "embed_query" call. */
    override suspend fun embedQuery(text: String): FloatArray =
        calls.record("embed_query", 1, { delegate.embedQuery(text) })

    /** Uses the wrapped embedder's text, so logs show the real provider. */
    override fun toString(): String = delegate.toString()
}

private val providerHttpLog = LoggerFactory.getLogger("com.carevoice.alert.infrastructure.guidance.ProviderHttp")
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
