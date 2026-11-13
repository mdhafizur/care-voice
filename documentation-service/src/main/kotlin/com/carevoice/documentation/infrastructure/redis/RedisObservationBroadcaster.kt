package com.carevoice.documentation.infrastructure.redis

import com.carevoice.documentation.application.ObservationBroadcaster
import com.carevoice.documentation.application.ObservationRecord
import com.carevoice.documentation.infrastructure.serialization.StorageJson
import com.carevoice.documentation.infrastructure.serialization.StoredObservation
import com.carevoice.documentation.infrastructure.serialization.toRecord
import com.carevoice.documentation.infrastructure.serialization.toStored
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/**
 * Observer pattern across replicas: SSE fan-out over Redis pub/sub.
 *
 * One path per event, so no duplicates: when the publish to Redis succeeds, this replica does NOT also deliver
 * locally (it receives its own message from Redis like every other replica); only when the publish fails does it
 * deliver locally, so at least this replica's clients still get the event. Clients on other replicas then catch up
 * through `Last-Event-ID` replay on their next reconnect.
 */
class RedisObservationBroadcaster(private val redis: RedisConnections) : ObservationBroadcaster {
    private val log = LoggerFactory.getLogger(RedisObservationBroadcaster::class.java)
    private val listeners = CopyOnWriteArrayList<(ObservationRecord) -> Unit>()

    init {
        redis.subscribe(CHANNEL) { raw ->
            runCatching { StorageJson.decodeFromString(StoredObservation.serializer(), raw).toRecord() }
                .onSuccess(::deliver)
                .onFailure {
                    log.event(
                        Level.WARN,
                        LogType.EXTERNAL_REQUEST,
                        "Ignoring malformed observation message",
                        "error" to it.javaClass.simpleName,
                    )
                }
        }
    }

    /** Publishes each record through Redis; a record whose publish fails goes to local listeners only. */
    override suspend fun publish(records: List<ObservationRecord>) {
        records.forEach { record ->
            try {
                redis.publish(CHANNEL, StorageJson.encodeToString(StoredObservation.serializer(), record.toStored()))
                log.event(
                    Level.DEBUG,
                    LogType.EXTERNAL_REQUEST,
                    "Observation published for live feeds",
                    "observationId" to record.id,
                    "wardId" to record.wardId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.event(
                    Level.WARN,
                    LogType.EXTERNAL_REQUEST,
                    "SSE fan-out via Redis failed, delivering locally",
                    "error" to e.javaClass.simpleName,
                )
                deliver(record)
            }
        }
    }

    /** Adds a local listener for observations from every replica. */
    override fun subscribe(listener: (ObservationRecord) -> Unit) {
        listeners += listener
    }

    /** Passes the record to all local listeners, which feed the live (SSE) streams. */
    private fun deliver(record: ObservationRecord) {
        log.event(
            Level.DEBUG,
            LogType.EXTERNAL_REQUEST,
            "Observation received for live feeds",
            "observationId" to record.id,
            "wardId" to record.wardId,
        )
        listeners.forEach { it(record) }
    }

    companion object {
        const val CHANNEL = "carevoice:observations"
    }
}
