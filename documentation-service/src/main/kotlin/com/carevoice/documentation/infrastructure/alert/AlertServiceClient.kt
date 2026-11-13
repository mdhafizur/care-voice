package com.carevoice.documentation.infrastructure.alert

import com.carevoice.documentation.infrastructure.http.HttpFailures
import com.carevoice.documentation.outbox.EventEnvelope
import com.carevoice.documentation.outbox.EventPublisher
import com.carevoice.documentation.outbox.OutboxEvent
import com.carevoice.documentation.outbox.PublishResult
import com.carevoice.documentation.resilience.CircuitBreakerSettings
import com.carevoice.documentation.resilience.CircuitBreakers
import com.carevoice.documentation.resilience.Guarded
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.RetryAfter
import com.carevoice.documentation.resilience.event
import com.carevoice.documentation.resilience.guard
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Publishes outbox events to alert-service (`POST /v1/events/observations`, docs/CONTRACTS.md sections 8 and 9).
 *
 * Classification (what the relay should do next):
 * | Response                                   | Result                                   |
 * |--------------------------------------------|------------------------------------------|
 * | 2xx (202 first delivery, 200 duplicate)    | Published                                |
 * | 429, 503                                   | Retryable, honouring `Retry-After`       |
 * | other 5xx, timeout, connection error       | Retryable                                |
 * | other 4xx                                  | NonRetryable: the same bytes will never be accepted |
 * | circuit breaker open                       | CircuitOpen, without calling             |
 *
 * 200 on a duplicate is a success on purpose: the relay delivers at least once, so a re-delivery after a lost response
 * is expected, and alert-service deduplicates on the `Idempotency-Key`.
 *
 * Only Retryable outcomes count as failures for the circuit breaker. A 4xx means alert-service is healthy and rejected
 * one bad event; letting a few malformed events open the breaker would block every good event too.
 *
 * The trailing parameters have defaults (contract values) and exist for tests and tuning. The per-request timeout is
 * set explicitly (rather than relying on how the shared client was built), so the contract's 2 s holds even when the
 * client is shared with an adapter that needs a different timeout.
 *
 * Pattern: Adapter (implements the outbox's [EventPublisher] port over HTTP).
 *
 * @param serviceToken returns a valid `SERVICE` JWT; the caller caches and refreshes it (5-minute TTL).
 */
class AlertServiceClient(
    baseUrl: String,
    private val httpClient: HttpClient,
    private val serviceToken: suspend () -> String,
    meterRegistry: MeterRegistry,
    circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(),
    private val requestTimeout: Duration = 2.seconds,
    private val clock: Clock = Clock.systemUTC(),
) : EventPublisher {
    private val endpoint = baseUrl.trimEnd('/') + PATH

    /** Exposed for health checks and tests (state, metrics). */
    val circuitBreaker: CircuitBreaker = CircuitBreakers.create(
        CIRCUIT_BREAKER_NAME,
        circuitBreakerSettings,
        meterRegistry,
    )

    /**
     * Sends one event to alert-service behind the circuit breaker (a switch that stops calls while the service keeps
     * failing). A missing service token gives Retryable; an open breaker gives CircuitOpen without a call.
     */
    override suspend fun publish(event: OutboxEvent): PublishResult {
        // Fetched outside the breaker: a local token problem says nothing about alert-service's health.
        val token =
            try {
                serviceToken()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.event(Level.ERROR, LogType.EXTERNAL_REQUEST, "Could not obtain a service token", cause = e)
                return PublishResult.Retryable("service token unavailable")
            }
        val guarded = circuitBreaker.guard(isFailure = { it is PublishResult.Retryable }) { send(event, token) }
        return when (guarded) {
            is Guarded.Completed -> guarded.value
            Guarded.Rejected -> PublishResult.CircuitOpen
        }
    }

    /** Sends the HTTP POST with the token, idempotency key and correlation headers; transport errors are Retryable. */
    private suspend fun send(event: OutboxEvent, token: String): PublishResult = try {
        // Step 1: POST the event with a time limit, the service token and the tracking headers.
        val mark = TimeSource.Monotonic.markNow()
        val response =
            httpClient.post(endpoint) {
                timeout {
                    requestTimeoutMillis = requestTimeout.inWholeMilliseconds
                    connectTimeoutMillis = requestTimeout.inWholeMilliseconds
                    socketTimeoutMillis = requestTimeout.inWholeMilliseconds
                }
                bearerAuth(token)
                // The consumer deduplicates on this key, which is what makes at-least-once delivery safe.
                header(IDEMPOTENCY_KEY, EventEnvelope.eventIdOf(event))
                // Correlation with the nurse's original request (see OutboxEvent); validated values only.
                EventEnvelope.requestIdOf(event)?.let { header(REQUEST_ID_HEADER, it) }
                EventEnvelope.traceparentOf(event)?.let { header(TRACEPARENT_HEADER, it) }
                // TextContent sends the stored JSON byte for byte, bypassing ContentNegotiation: the payload was
                // serialised once, with ContractJson, when the observation was committed.
                setBody(TextContent(event.payload, ContentType.Application.Json))
            }
        // Step 2: log the status and how long the call took.
        log.event(
            Level.DEBUG,
            LogType.EXTERNAL_REQUEST,
            "alert-service event POST finished",
            "url" to endpoint,
            "eventId" to EventEnvelope.eventIdOf(event),
            "httpStatus" to response.status.value,
            "durationMs" to mark.elapsedNow().inWholeMilliseconds,
        )
        // Step 3: turn the HTTP status into a result: published, retry later, or do not retry.
        classify(response.status, response.headers[HttpHeaders.RetryAfter])
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Anything else that prevents a response (a CIO engine error that is not an IOException, for example)
        // is treated as transient too: the event itself is fine, the delivery failed.
        val failure = HttpFailures.classify(e)
        PublishResult.Retryable(failure?.reason ?: "unexpected client error: ${e::class.simpleName}")
    }

    /** Maps the HTTP status to a [PublishResult], following the table in the class comment. */
    private fun classify(status: HttpStatusCode, retryAfterHeader: String?): PublishResult = when {
        status.value in 200..299 -> PublishResult.Published

        status == HttpStatusCode.TooManyRequests || status == HttpStatusCode.ServiceUnavailable ->
            PublishResult.Retryable("HTTP ${status.value}", RetryAfter.parse(retryAfterHeader, clock.instant()))

        status.value >= 500 -> PublishResult.Retryable("HTTP ${status.value}")

        // 1xx/3xx are not expected from this endpoint (redirects are not followed for POST); treat them like 4xx.
        else -> PublishResult.NonRetryable("HTTP ${status.value}")
    }

    companion object {
        const val PATH = "/v1/events/observations"
        const val CIRCUIT_BREAKER_NAME = "alert-service"
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val TRACEPARENT_HEADER = "traceparent"

        private val log = LoggerFactory.getLogger(AlertServiceClient::class.java)
    }
}
