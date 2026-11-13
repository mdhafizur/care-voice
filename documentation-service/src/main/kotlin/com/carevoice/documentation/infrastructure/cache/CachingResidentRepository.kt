package com.carevoice.documentation.infrastructure.cache

import com.carevoice.documentation.application.CacheInvalidation
import com.carevoice.documentation.application.Resident
import com.carevoice.documentation.application.ResidentRepository
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Duration

/**
 * Decorator pattern: wraps the Postgres [ResidentRepository] with a local Caffeine cache (cache-aside, L1).
 *
 * Why it pays off: every transcript and every list request looks up its resident, and decrypting the display name
 * (AES-GCM) plus a database round trip per request is wasted work for data that almost never changes.
 *
 * Why it is safe across replicas: entries expire after [ttl] at the latest, and [invalidate] is called from the Redis
 * invalidation bus on every replica, so no cache is a source of truth. Decrypted names live only in this process's
 * memory, never in Redis. Misses (unknown ids) are not cached, so a resident created later is found immediately.
 */
class CachingResidentRepository(
    private val delegate: ResidentRepository,
    meterRegistry: MeterRegistry,
    ttl: Duration = Duration.ofMinutes(5),
    maximumSize: Long = 10_000,
) : ResidentRepository {
    private val cache: Cache<Key, Resident> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(maximumSize)
            .recordStats()
            .build()

    private val log = LoggerFactory.getLogger(CachingResidentRepository::class.java)

    init {
        CaffeineCacheMetrics.monitor(meterRegistry, cache, CACHE_NAME)
    }

    /** Returns the cached resident, or loads it from the delegate and caches it; unknown ids are not cached. */
    override suspend fun findById(tenantId: String, residentId: String): Resident? {
        val key = Key(tenantId, residentId)
        cache.getIfPresent(key)?.let {
            log.event(Level.DEBUG, LogType.DB_OPERATION, "Resident cache hit", "residentId" to residentId)
            return it
        }
        val loaded = delegate.findById(tenantId, residentId)?.also { cache.put(key, it) }
        log.event(
            Level.DEBUG,
            LogType.DB_OPERATION,
            "Resident cache miss",
            "residentId" to residentId,
            "found" to (loaded != null),
        )
        return loaded
    }

    /** Handles bus messages; other cache names are ignored. */
    fun onInvalidation(message: CacheInvalidation) {
        if (message is CacheInvalidation.ResidentEntry) invalidate(message.residentId)
    }

    /** Removes every cached entry with this resident id, whatever the tenant. */
    fun invalidate(residentId: String) {
        val removed = cache.asMap().keys.removeIf { it.residentId == residentId }
        log.event(
            Level.DEBUG,
            LogType.DB_OPERATION,
            "Resident cache invalidated",
            "residentId" to residentId,
            "hadEntry" to removed,
        )
    }

    private data class Key(val tenantId: String, val residentId: String)

    companion object {
        const val CACHE_NAME = "carevoice_resident"
    }
}
