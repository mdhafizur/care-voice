package com.carevoice.alert.api

import com.carevoice.alert.application.EventParser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.sync.Semaphore

/**
 * `POST /v1/events/observations`.
 *
 * Backpressure: a `Semaphore(64)` bounds concurrent processing per instance, and `tryAcquire` NEVER waits — when all
 * slots are busy the call is answered immediately with 503 + `Retry-After: 1`. Queueing instead would let latency and
 * memory grow without bound under a burst (and hold the caller's connection while it waits); the outbox relay treats
 * 503 as retryable and backs off, so rejecting is lossless. Deliberately per instance: it protects this pod's own
 * Mongo pool and heap. Not behind the per-user rate limit: the only caller is the relay, whose throughput is governed
 * by this semaphore and its own backoff.
 */
internal fun Route.eventRoutes(api: AlertApi) {
    val slots = Semaphore(api.eventConcurrency)
    post {
        // Step 1: read the request body.
        val body = call.receiveJsonText()
        // Step 2: take a free processing slot, or answer 503 at once when all are busy (see above).
        if (!slots.tryAcquire()) throw ApiException.Overloaded()
        try {
            // Step 3: parse and process the event; `finally` always gives the slot back.
            val event = EventParser.parse(body)
            val result = api.processObservationEvent.execute(event)
            // Step 4: a duplicate event answers 200, a new one 202 (accepted).
            val status = if (result.duplicate) HttpStatusCode.OK else HttpStatusCode.Accepted
            call.respondText(
                ApiJson.encodeToString(EventAcceptedDto.serializer(), AlertDtoMapper.toDto(result)),
                ContentType.Application.Json,
                status,
            )
        } finally {
            slots.release()
        }
    }
}
