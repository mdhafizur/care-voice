package com.carevoice.documentation.infrastructure.redis

import com.carevoice.documentation.application.CacheInvalidation
import com.carevoice.documentation.application.CacheInvalidationBus
import com.carevoice.documentation.infrastructure.serialization.StorageJson
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

    /** Publishes to every replica through Redis; when that fails, invalidates only this replica's caches. */
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

    /** Adds a local listener; it gets messages from every replica, this one included. */
    override fun subscribe(listener: (CacheInvalidation) -> Unit) {
        listeners += listener
    }

    /** Passes the message to all local listeners. */
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
        // Step 1: parse the JSON. Bad JSON gives null instead of an exception.
        val wire =
            runCatching { StorageJson.decodeFromString(InvalidationMessage.serializer(), raw) }.getOrNull()
        // Step 2: map the cache name to an invalidation. An unknown cache name gives null.
        val message =
            wire?.let {
                when (it.cache) {
                    OBSERVATION_QUERY -> it.observationQuery()
                    RESIDENT -> it.id?.let { id -> CacheInvalidation.ResidentEntry(id) }
                    else -> null
                }
            }
        // Step 3: log a message that cannot be used, and ignore it.
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

    /** Builds an observation-query invalidation, or null when tenant or ward is missing. */
    private fun InvalidationMessage.observationQuery(): CacheInvalidation? =
        if (tenant != null && ward != null) CacheInvalidation.ObservationQuery(tenant, ward) else null

    /** Converts the message to its wire format, which carries only ids. */
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
