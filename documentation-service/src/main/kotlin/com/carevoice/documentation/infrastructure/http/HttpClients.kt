package com.carevoice.documentation.infrastructure.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The one place outbound HTTP clients are configured, so every adapter gets the same, deliberate defaults.
 */
object HttpClients {
    /**
     * JSON for request and response bodies: tolerant of fields a newer server adds (forward compatibility), and
     * `explicitNulls = false` so optional fields are omitted rather than sent as null.
     */
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    /**
     * Creates a CIO-engine client (pure Kotlin coroutines, no extra thread pool per request).
     *
     * - Timeouts: [requestTimeout] bounds the whole call; connect and socket timeouts are the same value so neither
     *   a dead host (SYN never answered) nor a stalled response (connection open, no bytes) can exceed it. Without
     *   them a call to a hanging dependency would wait forever and pin a worker.
     * - No automatic retries (the CIO engine and Ktor do not retry unless HttpRequestRetry is installed, and it is
     *   not): every retry in this service is explicit, bounded and visible in metrics, in the outbox relay or in
     *   [com.carevoice.documentation.resilience.retry]. Hidden retries multiply load on a struggling dependency and
     *   distort latency metrics.
     * - `expectSuccess = false`: a 4xx/5xx is a normal response the adapter classifies, not an exception.
     *
     * The returned client owns a connection pool; create it once at startup, share it, and close it on shutdown.
     */
    fun create(requestTimeout: Duration = 2.seconds): HttpClient {
        require(requestTimeout.isPositive()) { "requestTimeout must be positive but was $requestTimeout" }
        val timeoutMillis = requestTimeout.inWholeMilliseconds
        return HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = timeoutMillis
                socketTimeoutMillis = timeoutMillis
            }
            install(ContentNegotiation) {
                json(json)
            }
        }
    }
}
