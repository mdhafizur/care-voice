package com.carevoice.documentation.api.routes

import com.carevoice.documentation.api.ApiJson
import com.carevoice.documentation.api.Dependencies
import com.carevoice.documentation.api.LAST_EVENT_ID_HEADER
import com.carevoice.documentation.api.ObservationDto
import com.carevoice.documentation.api.plugins.caller
import com.carevoice.documentation.api.toDto
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent

/**
 * `GET /v1/observations/stream` (Server-Sent Events). Must be mounted inside `authenticate(JWT_AUTH)` with the
 * RoleGuard, so 401/403 are answered as problem responses before the stream starts.
 *
 * Each event is `event: observation`, `id: <observationId>`, `data: <observation JSON>`. The id is what a client
 * sends back as `Last-Event-ID` after a reconnect (e.g. when its pod is drained), and the missed observations of its
 * ward are replayed from Postgres, so no sticky sessions are needed (docs/CONTRACTS.md, section 9b). A heartbeat
 * comment every 15 s keeps proxies from closing an idle connection. Ktor's SSE content already sends
 * `Cache-Control: no-store`.
 */
fun Route.streamRoutes(deps: Dependencies) {
    sse(OBSERVATION_STREAM_PATH) {
        // Step 1: send a heartbeat comment on a timer, so the connection never looks idle.
        heartbeat {
            period = deps.http.sseHeartbeat
            event = ServerSentEvent(comments = "heartbeat")
        }
        // Step 2: find out who is calling, and the last event id they saw before a reconnect (if any).
        val caller = call.caller()
        val lastEventId = call.request.header(LAST_EVENT_ID_HEADER)
        // Step 3: replay what was missed, then send each new observation of the ward as one event.
        deps.observationStream.subscribe(caller, lastEventId).collect { record ->
            send(
                ServerSentEvent(
                    data = ApiJson.encodeToString(ObservationDto.serializer(), record.toDto()),
                    event = OBSERVATION_EVENT,
                    id = record.id.toString(),
                ),
            )
        }
    }
}

/** Long-lived SSE stream: exempt from the request deadline. */
const val OBSERVATION_STREAM_PATH = "/v1/observations/stream"

const val OBSERVATION_EVENT = "observation"
