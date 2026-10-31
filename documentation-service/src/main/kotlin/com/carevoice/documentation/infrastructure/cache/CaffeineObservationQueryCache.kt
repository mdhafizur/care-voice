package com.carevoice.documentation.infrastructure.cache

import com.carevoice.documentation.application.CacheInvalidation
import com.carevoice.documentation.application.ObservationQueryCache
import com.carevoice.documentation.application.ObservationQueryKey
import com.carevoice.documentation.application.ObservationRecord
import com.carevoice.documentation.application.Page
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
 * Adapter: local cache of ward-scoped observation query pages (cache-aside), 10 s TTL.
 *
 * The short TTL is the safety net: if an invalidation message is lost (Redis down), a page is stale for at most
 * 10 s. Normally a new observation in a ward invalidates that ward's entries on every replica right after commit.
 * Keys always include tenant and ward, so one ward can never be served another ward's cached page.
 */
class CaffeineObservationQueryCache(
    meterRegistry: MeterRegistry,
    ttl: Duration = Duration.ofSeconds(10),
    maximumSize: Long = 5_000,
) : ObservationQueryCache {
    private val cache: Cache<ObservationQueryKey, Page<ObservationRecord>> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(maximumSize)
            .recordStats()
            .build()

    private val log = LoggerFactory.getLogger(CaffeineObservationQueryCache::class.java)

    init {
        CaffeineCacheMetrics.monitor(meterRegistry, cache, CACHE_NAME)
    }

    override suspend fun getOrLoad(
        key: ObservationQueryKey,
        loader: suspend () -> Page<ObservationRecord>,
    ): Page<ObservationRecord> {
        cache.getIfPresent(key)?.let {
            log.event(Level.DEBUG, LogType.DB_OPERATION, "Observation query cache hit", "wardId" to key.wardId)
            return it
        }
        log.event(Level.DEBUG, LogType.DB_OPERATION, "Observation query cache miss", "wardId" to key.wardId)
        return loader().also { cache.put(key, it) }
    }

    override fun invalidateWard(tenantId: String, wardId: String) {
        val removed = cache.asMap().keys.removeIf { it.tenantId == tenantId && it.wardId == wardId }
        log.event(
            Level.DEBUG,
            LogType.DB_OPERATION,
            "Observation query cache invalidated",
            "wardId" to wardId,
            "hadEntries" to removed,
        )
    }

    /** Handles bus messages; other cache names are ignored. */
    fun onInvalidation(message: CacheInvalidation) {
        if (message is CacheInvalidation.ObservationQuery) invalidateWard(message.tenantId, message.wardId)
    }

    companion object {
        const val CACHE_NAME = "carevoice_observation_query"
    }
}
