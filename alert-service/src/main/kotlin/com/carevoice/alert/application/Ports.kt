package com.carevoice.alert.application

import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.AlertStatus
import com.carevoice.alert.domain.WardScope
import java.time.Instant
import java.util.UUID

/**
 * Repository pattern (port): what the use cases need from alert storage, in domain terms. The Mongo adapter lives in
 * `infrastructure/persistence`; tests can use an in-memory fake. Every read is scoped to a [WardScope], so tenant and
 * ward isolation cannot be forgotten by a caller.
 */
interface AlertRepository {
    /**
     * Inserts [alerts], silently skipping any whose `eventId + rule` already exists (unique index), and returns the
     * alerts that were actually inserted. Reprocessing a redelivered event is therefore harmless.
     */
    suspend fun insertAll(alerts: List<Alert>): List<Alert>

    /** Returns the alert only when it belongs to [scope]; null when it is missing or in another ward. */
    suspend fun findById(scope: WardScope, alertId: String): Alert?

    /**
     * One keyset page. Order is `createdAt` then `id`.
     * - [keyset] null: the newest [limit] alerts, newest first.
     * - [Keyset.Direction.OLDER]: alerts strictly older than the key, newest first.
     * - [Keyset.Direction.NEWER]: alerts strictly newer than the key, OLDEST first (the caller reverses them).
     */
    suspend fun findPage(scope: WardScope, status: AlertStatus?, keyset: Keyset?, limit: Int): List<Alert>

    /** Counts the ward's alerts across all pages, optionally only those with [status]. */
    suspend fun count(scope: WardScope, status: AlertStatus?): Long

    /**
     * Optimistic locking: sets the alert to ACKNOWLEDGED only if it is still OPEN at [expectedVersion], incrementing
     * the version, in one atomic conditional update. Returns the updated alert, or null when the condition did not hold
     * (concurrent modification, or the alert vanished).
     */
    suspend fun acknowledge(
        scope: WardScope,
        alertId: String,
        expectedVersion: Long,
        acknowledgedBy: String,
        acknowledgedAt: Instant,
    ): Alert?
}

/** Position for keyset pagination: `(createdAt, id)` of the boundary alert. */
data class Keyset(val createdAt: Instant, val id: String, val direction: Direction) {
    enum class Direction { OLDER, NEWER }
}

/**
 * Idempotency record of one processed event.
 *
 * @property completed false while (or if) the alerts of the event have not all been written yet; a later delivery that
 *   finds an incomplete record finishes the work instead of trusting it, so a crash between the two writes never
 *   loses an alert.
 */
data class ProcessedEvent(
    val eventId: String,
    val tenantId: String,
    val wardId: String,
    val observationId: String,
    val alertsCreated: Int,
    val processedAt: Instant,
    val completed: Boolean,
)

/** Repository pattern (port) for the `processed_events` idempotency records. */
interface ProcessedEventRepository {
    /**
     * Inserts [event]; returns false when a record with the same eventId already exists. Backed by a unique index, so
     * two pods racing on the same event cannot both win (never check-then-insert).
     */
    suspend fun tryInsert(event: ProcessedEvent): Boolean

    /** Returns the record of the event, or null when the event was never stored. */
    suspend fun find(eventId: String): ProcessedEvent?

    /** Marks the record as completed, after all alerts of the event are written. */
    suspend fun markCompleted(eventId: String)
}

/** Adapter pattern (port) for id generation, injected so tests are deterministic. */
fun interface IdGenerator {
    /** Returns a new, unique id. */
    fun next(): String

    companion object {
        val RANDOM_UUID: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    }
}

/** Business metrics the use cases report (port; the Micrometer adapter lives in `observability/`). */
interface AlertMetrics {
    /** Reports the alerts that were actually inserted; skipped duplicates are not included. */
    fun alertsCreated(alerts: List<Alert>)

    /** Counts one processed event; [duplicate] is true when the event had been processed before. */
    fun eventProcessed(duplicate: Boolean)

    companion object {
        val NONE: AlertMetrics =
            object : AlertMetrics {
                /** Records nothing. */
                override fun alertsCreated(alerts: List<Alert>) = Unit

                /** Records nothing. */
                override fun eventProcessed(duplicate: Boolean) = Unit
            }
    }
}
