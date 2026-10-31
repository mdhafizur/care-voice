package com.carevoice.documentation.infrastructure.redis

import com.carevoice.documentation.application.CacheInvalidation
import com.carevoice.documentation.application.CacheInvalidationBus
import com.carevoice.documentation.application.ObservationBroadcaster
import com.carevoice.documentation.application.ObservationRecord
import com.carevoice.documentation.infrastructure.serialization.StorageJson
import com.carevoice.documentation.infrastructure.serialization.StoredObservation
import com.carevoice.documentation.infrastructure.serialization.toRecord
import com.carevoice.documentation.infrastructure.serialization.toStored
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/** Wire format on `carevoice:cache-invalidation`. Ids only (docs/CONTRACTS.md, section 9a). */
@Serializable
internal data class InvalidationMessage(
    val cache: String,
    val tenant: String? = null,
    val ward: String? = null,
    val id: String? = null,
)

/**
 * Observer pattern across replicas: cache invalidation over Redis pub/sub.
 *
 * Every replica, INCLUDING the publisher, receives the message and invalidates its local Caffeine entries, so there
 * is one code path. When Redis is unavailable the publisher invalidates locally only, and other replicas rely on the
 * short cache TTLs (degraded, never wrong for long).
 */
class RedisCacheInvalidationBus(private val redis: RedisConnections) : CacheInvalidationBus {
    private val log = LoggerFactory.getLogger(RedisCacheInvalidationBus::class.java)
    private val listeners = CopyOnWriteArrayList<(CacheInvalidation) -> Unit>()

    init {
        redis.subscribe(CHANNEL) { raw -> decode(raw)?.let(::deliver) }
    }

    override suspend fun publish(message: CacheInvalidation) {
        try {
            redis.publish(CHANNEL, StorageJson.encodeToString(InvalidationMessage.serializer(), message.toWire()))
            log.event(
                Level.DEBUG,
                LogType.EXTERNAL_REQUEST,
                "Cache invalidation published",
                "cache" to message.toWire().cache,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.event(
                Level.WARN,
                LogType.EXTERNAL_REQUEST,
                "Cache invalidation via Redis failed, invalidating locally",
                "error" to e.javaClass.simpleName,
            )
            deliver(message)
        }
    }

    override fun subscribe(listener: (CacheInvalidation) -> Unit) {
        listeners += listener
    }

    private fun deliver(message: CacheInvalidation) {
        log.event(
            Level.DEBUG,
            LogType.EXTERNAL_REQUEST,
            "Cache invalidation received",
            "cache" to message.toWire().cache,
            "listeners" to listeners.size,
        )
        listeners.forEach { it(message) }
    }

    /**
     * The message, or null for one this version cannot use. Only our own services publish on this channel, so an
     * unusable message means a version mismatch during a rollout or a bug: logged (length only, never the content),
     * and that cache then falls back to its TTL.
     */
    private fun decode(raw: String): CacheInvalidation? {
        val wire =
            runCatching { StorageJson.decodeFromString(InvalidationMessage.serializer(), raw) }.getOrNull()
        val message =
            wire?.let {
                when (it.cache) {
                    OBSERVATION_QUERY -> it.observationQuery()
                    RESIDENT -> it.id?.let { id -> CacheInvalidation.ResidentEntry(id) }
                    else -> null
                }
            }
        if (message == null) {
            log.event(
                Level.WARN,
                LogType.EXTERNAL_REQUEST,
                "Ignoring unusable cache invalidation message",
                "channel" to CHANNEL,
                "cache" to wire?.cache,
                "length" to raw.length,
            )
        }
        return message
    }

    private fun InvalidationMessage.observationQuery(): CacheInvalidation? =
        if (tenant != null && ward != null) CacheInvalidation.ObservationQuery(tenant, ward) else null

    private fun CacheInvalidation.toWire(): InvalidationMessage = when (this) {
        is CacheInvalidation.ObservationQuery -> InvalidationMessage(
            OBSERVATION_QUERY,
            tenant = tenantId,
            ward = wardId,
        )

        is CacheInvalidation.ResidentEntry -> InvalidationMessage(RESIDENT, id = residentId)
    }

    companion object {
        const val CHANNEL = "carevoice:cache-invalidation"
        const val OBSERVATION_QUERY = "observation-query"
        const val RESIDENT = "resident"
    }
}

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

    override fun subscribe(listener: (ObservationRecord) -> Unit) {
        listeners += listener
    }

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

/** Single-instance implementations (tests, or running without Redis): deliver directly to local listeners. */
class LocalCacheInvalidationBus : CacheInvalidationBus {
    private val listeners = CopyOnWriteArrayList<(CacheInvalidation) -> Unit>()

    override suspend fun publish(message: CacheInvalidation) = listeners.forEach { it(message) }

    override fun subscribe(listener: (CacheInvalidation) -> Unit) {
        listeners += listener
    }
}

class LocalObservationBroadcaster : ObservationBroadcaster {
    private val listeners = CopyOnWriteArrayList<(ObservationRecord) -> Unit>()

    override suspend fun publish(records: List<ObservationRecord>) = records.forEach { record ->
        listeners.forEach { it(record) }
    }

    override fun subscribe(listener: (ObservationRecord) -> Unit) {
        listeners += listener
    }
}
