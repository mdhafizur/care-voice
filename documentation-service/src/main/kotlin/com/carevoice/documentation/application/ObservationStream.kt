package com.carevoice.documentation.application

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID

/**
 * Live observations for SSE clients (`GET /v1/observations/stream`).
 *
 * Observer pattern: one HOT [MutableSharedFlow] per instance is fed by the [ObservationBroadcaster] (Redis pub/sub
 * across replicas, or local delivery when Redis is down); every connected client is a subscriber that sees only its
 * own tenant and ward.
 *
 * Backpressure: the flow never suspends the publisher. Its buffer drops the OLDEST event when a subscriber is too
 * slow (DROP_OLDEST), so one stalled phone cannot slow down documentation for everyone; a client that missed events
 * reconnects with `Last-Event-ID` and gets them replayed from Postgres, which is the source of truth.
 *
 * Replay without gaps: a client's live subscription is registered BEFORE the replay query runs, and events already
 * replayed are skipped when they also arrive live, so an observation committed during the replay is neither lost
 * nor sent twice.
 */
class ObservationStream(
    broadcaster: ObservationBroadcaster,
    private val observations: ObservationRepository,
    bufferCapacity: Int = DEFAULT_BUFFER,
) {
    private val events =
        MutableSharedFlow<ObservationRecord>(
            replay = 0,
            extraBufferCapacity = bufferCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val closing = MutableStateFlow(false)
    private val log = LoggerFactory.getLogger(ObservationStream::class.java)

    init {
        broadcaster.subscribe { record -> events.tryEmit(record) }
    }

    /** The hot flow of every observation this instance hears about (all wards); for metrics and tests. */
    val all: Flow<ObservationRecord> = events.asSharedFlow()

    /** Number of currently connected stream subscribers on this instance. */
    val subscriberCount: Int get() = events.subscriptionCount.value

    /**
     * The caller's stream: replayed observations after [lastEventId] (when given), then live ones of the caller's
     * ward. Completes normally when [closeAll] is called (graceful shutdown), so clients reconnect elsewhere.
     */
    fun subscribe(caller: Caller, lastEventId: String?): Flow<ObservationRecord> = channelFlow {
        // Step 1: only logged-in staff may open the live feed.
        caller.requireAnyRole()
        // Step 2: listen for live events the caller may see, in a small buffer per client. When the buffer is full,
        // the oldest event is dropped, so a slow client cannot hold up the others.
        val live =
            Channel<ObservationRecord>(capacity = PER_CLIENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        // UNDISPATCHED: the subscription is registered before this line returns, i.e. before the replay query.
        val liveJob =
            launch(start = CoroutineStart.UNDISPATCHED) {
                // trySend: never suspends (DROP_OLDEST) and does not throw once the channel is closed at shutdown.
                events.collect { if (caller.canSee(it.tenantId, it.wardId)) live.trySend(it) }
            }
        // Step 3: end the feed when the server starts to shut down.
        val closeJob = launch { closing.first { it }.also { live.close() } }

        // Step 4: after a reconnect the client sends the last event id it saw. Send what it missed first,
        // at most MAX_REPLAY records, and remember their ids.
        val replayedIds = HashSet<UUID>()
        lastEventId?.let(::parseUuid)?.let { after ->
            observations.findAfter(caller.tenantId, caller.wardId, after, MAX_REPLAY).forEach {
                replayedIds += it.id
                send(it)
            }
        }
        log.event(
            Level.DEBUG,
            LogType.BUSINESS,
            "Live feed subscribed",
            "wardId" to caller.wardId,
            "lastEventIdSent" to (lastEventId != null),
            "replayed" to replayedIds.size,
            "subscribers" to subscriberCount,
        )
        // Step 5: pass on live events, but skip any the replay already sent, because one event can be in both.
        var delivered = 0
        for (record in live) {
            if (record.id !in replayedIds) {
                send(record)
                delivered++
            }
        }
        // Step 6: the feed is closed. Log it and stop the helper jobs.
        log.event(
            Level.DEBUG,
            LogType.BUSINESS,
            "Live feed closed",
            "wardId" to caller.wardId,
            "delivered" to delivered,
            "serverShutdown" to closing.value,
        )
        liveJob.cancel()
        closeJob.cancel()
    }

    /** Ends every open stream on this instance (graceful shutdown, docs/CONTRACTS.md, section 9b). */
    fun closeAll() {
        closing.value = true
    }

    companion object {
        const val DEFAULT_BUFFER = 1_024
        const val PER_CLIENT_BUFFER = 256

        /** Upper bound of a replay; a client that was away longer reloads the list instead. */
        const val MAX_REPLAY = 500
    }
}
