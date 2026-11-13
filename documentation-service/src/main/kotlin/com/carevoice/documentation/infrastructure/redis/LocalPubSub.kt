package com.carevoice.documentation.infrastructure.redis

import com.carevoice.documentation.application.CacheInvalidation
import com.carevoice.documentation.application.CacheInvalidationBus
import com.carevoice.documentation.application.ObservationBroadcaster
import com.carevoice.documentation.application.ObservationRecord
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single-instance version of [RedisCacheInvalidationBus] (tests, or running without Redis): delivers directly to
 * local listeners.
 */
class LocalCacheInvalidationBus : CacheInvalidationBus {
    private val listeners = CopyOnWriteArrayList<(CacheInvalidation) -> Unit>()

    /** Delivers the message straight to the local listeners. */
    override suspend fun publish(message: CacheInvalidation) = listeners.forEach { it(message) }

    /** Adds a local listener. */
    override fun subscribe(listener: (CacheInvalidation) -> Unit) {
        listeners += listener
    }
}

/**
 * Single-instance version of [RedisObservationBroadcaster] (tests, or running without Redis): delivers directly to
 * local listeners.
 */
class LocalObservationBroadcaster : ObservationBroadcaster {
    private val listeners = CopyOnWriteArrayList<(ObservationRecord) -> Unit>()

    /** Delivers each record straight to the local listeners. */
    override suspend fun publish(records: List<ObservationRecord>) = records.forEach { record ->
        listeners.forEach { it(record) }
    }

    /** Adds a local listener. */
    override fun subscribe(listener: (ObservationRecord) -> Unit) {
        listeners += listener
    }
}
