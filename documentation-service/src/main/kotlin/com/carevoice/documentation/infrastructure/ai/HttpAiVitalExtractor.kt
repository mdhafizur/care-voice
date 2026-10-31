package com.carevoice.documentation.infrastructure.ai

import com.carevoice.documentation.infrastructure.http.HttpFailures
import com.carevoice.documentation.infrastructure.http.TransportFailure
import com.carevoice.documentation.resilience.CircuitBreakerSettings
import com.carevoice.documentation.resilience.CircuitBreakers
import com.carevoice.documentation.resilience.Guarded
import com.carevoice.documentation.resilience.LatencyTimers
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.RetryPolicy
import com.carevoice.documentation.resilience.event
import com.carevoice.documentation.resilience.guard
import com.carevoice.documentation.resilience.retry
import com.carevoice.domain.extraction.AiVitalExtractor
import com.carevoice.domain.extraction.RawVitalCandidate
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * AI fallback for vital-sign extraction over a model's HTTP API. Pattern: Template Method. This class owns everything
 * that must behave the same for every provider (failure policy, retries, circuit breaker, metrics, logging);
 * [ClaudeVitalExtractor] and [GeminiVitalExtractor] supply only the wire format: [endpoint], [authenticate],
 * [requestBody] and [answerText]. It is an Adapter for the domain port [AiVitalExtractor] and the last link of the
 * Chain of Responsibility "rules, then AI".
 *
 * Failure policy, following the port's contract: the model is an optional helper, never a dependency the nurse has to
 * wait for. Every failure (timeout, 4xx/5xx, open breaker, unreadable answer) becomes `emptyList()`, so the
 * deterministic rule results stand on their own, and is counted in `carevoice_ai_fallback_total{result}`. Whatever
 * the model returns is untrusted: [VitalAnswerParser] reads it defensively and the domain validates every value with
 * the same smart constructors as the rule path (and marks it NEEDS_REVIEW).
 *
 * Resilience:
 * - Short per-attempt timeout ([AiSettings.timeout]) because a nurse is waiting for the HTTP response.
 * - One retry, only for 429 and 5xx (Anthropic's 529 and Google's 503 "overloaded" included), with a short jittered
 *   pause: those are the transient answers where a second attempt often succeeds. A timeout is not retried; it
 *   already used the whole latency budget.
 * - Its own circuit breaker per provider: while the API is failing, requests skip the model immediately instead of
 *   each waiting for a timeout.
 *
 * Privacy: the transcript is sent to the model (that is the feature) but never logged, not even on errors; logs carry
 * only status codes, durations and counts. API keys travel in headers and are never logged either.
 */
abstract class HttpAiVitalExtractor(
    protected val settings: AiSettings,
    private val httpClient: HttpClient,
    meterRegistry: MeterRegistry,
    circuitBreakerName: String,
    circuitBreakerSettings: CircuitBreakerSettings,
    private val retryPolicy: RetryPolicy,
) : AiVitalExtractor {
    /** Exposed for health checks and tests. */
    val circuitBreaker: CircuitBreaker =
        CircuitBreakers.create(circuitBreakerName, circuitBreakerSettings, meterRegistry)

    private val log: Logger = LoggerFactory.getLogger(javaClass)

    private val outcomes: Map<Outcome, Counter> =
        Outcome.entries.associateWith {
            Counter
                .builder(FALLBACK_TOTAL_METRIC)
                .description("AI fallback extractions by result")
                .tag("result", it.tag)
                .register(meterRegistry)
        }
    private val timers: Map<Outcome, Timer> =
        Outcome.entries.associateWith {
            LatencyTimers.timer(
                meterRegistry,
                REQUEST_SECONDS_METRIC,
                "Duration of one AI extraction",
                Tags.of("result", it.tag),
            )
        }

    /** Full URL of the generation endpoint. */
    protected abstract val endpoint: String

    /** Adds the provider's authentication (and version) headers. */
    protected abstract fun HttpRequestBuilder.authenticate()

    /** The JSON request for [normalized], built from [VitalExtractionPrompt]. */
    protected abstract fun requestBody(normalized: String): String

    /** The model's answer text from a 2xx body; null when the body is not a response of this API at all. */
    protected abstract fun answerText(responseBody: String): String?

    override suspend fun extract(normalized: String): List<RawVitalCandidate> {
        val mark = TimeSource.Monotonic.markNow()
        val result = extractClassified(normalized)
        val outcome = result.outcome
        val elapsed = mark.elapsedNow()
        outcomes.getValue(outcome).increment()
        timers.getValue(outcome).record(elapsed.toJavaDuration())
        // httpStatus and apiError say WHY a call failed (e.g. 429 RESOURCE_EXHAUSTED, 400 INVALID_ARGUMENT); they come
        // from the provider's error envelope, never from the answer text, so no transcript content is logged.
        val fields =
            listOfNotNull(
                "provider" to circuitBreaker.name,
                "result" to outcome.tag,
                "candidates" to result.candidates.size,
                "durationMs" to elapsed.inWholeMilliseconds,
                result.httpStatus?.let { "httpStatus" to it },
                result.apiError?.let { "apiError" to it },
            )
        log.event(
            if (outcome.isError) Level.WARN else Level.INFO,
            LogType.EXTERNAL_REQUEST,
            "AI extraction finished",
            *fields.toTypedArray(),
        )
        return result.candidates
    }

    private suspend fun extractClassified(normalized: String): Classified {
        if (normalized.isBlank()) return Classified(Outcome.EMPTY)
        val guarded =
            try {
                circuitBreaker.guard(isFailure = { it is Attempt.Failed && it.countsAsFailure }) {
                    retry(retryPolicy, isRetryable = { it is Attempt.Failed && it.retryable }) { attempt ->
                        val started = TimeSource.Monotonic.markNow()
                        call(normalized).also { logAttempt(attempt + 1, it, started.elapsedNow().inWholeMilliseconds) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A bug in this adapter must not fail the nurse's request either; the breaker has recorded it.
                log.event(Level.ERROR, LogType.EXTERNAL_REQUEST, "AI extraction failed unexpectedly", cause = e)
                return Classified(Outcome.ERROR)
            }
        val attempt =
            when (guarded) {
                Guarded.Rejected -> return Classified(Outcome.CIRCUIT_OPEN)
                is Guarded.Completed -> guarded.value
            }
        return when (attempt) {
            is Attempt.Failed ->
                Classified(attempt.outcome, httpStatus = attempt.httpStatus, apiError = attempt.apiError)

            is Attempt.Answered -> {
                val malformed = Classified(Outcome.MALFORMED, httpStatus = 200)
                val text = answerText(attempt.body) ?: return malformed
                val candidates = VitalAnswerParser.candidates(text) ?: return malformed
                Classified(if (candidates.isEmpty()) Outcome.EMPTY else Outcome.EXTRACTED, candidates)
            }
        }
    }

    private suspend fun call(normalized: String): Attempt = try {
        val response =
            httpClient.post(endpoint) {
                timeout {
                    requestTimeoutMillis = settings.timeout.inWholeMilliseconds
                    connectTimeoutMillis = settings.timeout.inWholeMilliseconds
                    socketTimeoutMillis = settings.timeout.inWholeMilliseconds
                }
                authenticate()
                setBody(TextContent(requestBody(normalized), ContentType.Application.Json))
            }
        val status = response.status.value
        when {
            status in 200..299 -> Attempt.Answered(response.bodyAsText())

            status == 429 || status >= 500 -> Attempt.Failed(
                Outcome.ERROR,
                retryable = true,
                countsAsFailure = true,
                httpStatus = status,
                apiError = ApiErrors.code(response.bodyAsText()),
            )

            // 400/401/403/404: our request, key or model name is wrong; retrying cannot help and it says nothing
            // about the API's availability, so the breaker does not count it.
            else -> Attempt.Failed(
                Outcome.ERROR,
                retryable = false,
                countsAsFailure = false,
                httpStatus = status,
                apiError = ApiErrors.code(response.bodyAsText()),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        when (HttpFailures.classify(e)) {
            TransportFailure.TIMEOUT -> Attempt.Failed(Outcome.TIMEOUT, retryable = false, countsAsFailure = true)

            // Connection errors, and any other engine failure that produced no response, are transient.
            TransportFailure.CONNECTION, null -> Attempt.Failed(
                Outcome.ERROR,
                retryable = true,
                countsAsFailure = true,
                apiError = e::class.simpleName,
            )
        }
    }

    /** DEBUG: one line per HTTP attempt, so a retry (e.g. 503 then 200) is visible, not only the final outcome. */
    private fun logAttempt(number: Int, attempt: Attempt, durationMs: Long) {
        log.event(
            Level.DEBUG,
            LogType.EXTERNAL_REQUEST,
            "AI attempt finished",
            "provider" to circuitBreaker.name,
            "model" to settings.model,
            "attempt" to number,
            "httpStatus" to if (attempt is Attempt.Failed) attempt.httpStatus else 200,
            "apiError" to (attempt as? Attempt.Failed)?.apiError,
            "retryable" to (attempt as? Attempt.Failed)?.retryable,
            "durationMs" to durationMs,
            "timeoutMs" to settings.timeout.inWholeMilliseconds,
        )
    }

    private sealed interface Attempt {
        class Answered(val body: String) : Attempt

        class Failed(
            val outcome: Outcome,
            val retryable: Boolean,
            val countsAsFailure: Boolean,
            val httpStatus: Int? = null,
            val apiError: String? = null,
        ) : Attempt
    }

    /** One extraction's outcome plus, for failures, the provider's status and error code (for the log line). */
    private class Classified(
        val outcome: Outcome,
        val candidates: List<RawVitalCandidate> = emptyList(),
        val httpStatus: Int? = null,
        val apiError: String? = null,
    )

    private enum class Outcome(val tag: String, val isError: Boolean) {
        EXTRACTED("extracted", false),
        EMPTY("empty", false),
        MALFORMED("malformed", true),
        TIMEOUT("timeout", true),
        ERROR("error", true),
        CIRCUIT_OPEN("circuit_open", true),
    }

    companion object {
        /** `carevoice_ai_fallback_total{result}`; one provider is active per process. */
        const val FALLBACK_TOTAL_METRIC = "carevoice.ai.fallback"

        /** `carevoice_ai_request_seconds{result}` (percentile histogram). */
        const val REQUEST_SECONDS_METRIC = "carevoice.ai.request"

        /** One retry for transient answers, with a short pause: the nurse is waiting. */
        val DEFAULT_RETRY_POLICY: RetryPolicy =
            RetryPolicy(maxAttempts = 2, base = 200.milliseconds, cap = 500.milliseconds)
    }
}
