package com.carevoice.documentation.outbox

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/** A row this relay instance owns until [leaseUntil]; the lease doubles as a fencing token (see [OutboxRepository]). */
internal data class ClaimedEvent(val event: OutboxEvent, val leaseUntil: Instant, val createdAt: Instant)

internal data class PendingStats(val count: Long, val oldestCreatedAt: Instant?)

/**
 * All SQL of the relay. Blocking JDBC; the relay calls it on Dispatchers.IO.
 *
 * Fencing: every update after a claim matches `next_attempt_at = <our lease>`. If this instance stalled past its lease
 * (GC pause, network partition) and another instance re-claimed the row, the other claim wrote a different lease, so
 * our late update matches zero rows and cannot overwrite the newer owner's outcome. The relay sees `false` and drops
 * the result; the event was then delivered twice, which the consumer's idempotency key absorbs.
 */
internal class OutboxRepository(private val dataSource: DataSource) {
    /**
     * Claims up to [limit] due rows and leases them until [leaseUntil], in one short transaction.
     *
     * Why `FOR UPDATE SKIP LOCKED`: several relay instances (one per service replica) poll the same table. Plain
     * `FOR UPDATE` would make the second instance WAIT for the first one's locks and then claim the same rows;
     * `SKIP LOCKED` makes it skip rows another transaction is claiming right now and take the next ones, so instances
     * share the work without blocking each other and without a coordinator.
     *
     * Why the lease instead of holding the lock while publishing: an HTTP call inside a database transaction would
     * keep a connection and row locks open for up to the full timeout, per row. Instead, the claim moves
     * `next_attempt_at` into the future and commits immediately; the row is then invisible to other claimers until
     * the lease expires. If this instance crashes mid-publish, nobody has to clean up: the lease simply runs out and
     * another instance picks the row up (that is the at-least-once guarantee).
     *
     * [now] comes from the application clock rather than SQL `now()`, the same clock [OutboxWriter] used for
     * `next_attempt_at`; that keeps "due" consistent with how rows were scheduled and makes tests deterministic.
     */
    fun claim(now: Instant, leaseUntil: Instant, limit: Int): List<ClaimedEvent> = transaction { connection ->
        connection.prepareStatement(CLAIM_SQL).use { statement ->
            statement.setObject(1, now.toDbTimestamp())
            statement.setInt(2, limit)
            statement.setObject(3, leaseUntil.toDbTimestamp())
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toClaimedEvent(leaseUntil)) }
            }
        }
    }.sortedBy { it.createdAt }

    /** Marks the row PUBLISHED; false when our lease no longer matches (another instance owns the row now). */
    fun markPublished(claimed: ClaimedEvent, now: Instant): Boolean = update(
        "UPDATE outbox SET status = 'PUBLISHED', published_at = ?, last_error = NULL $FENCE",
        claimed,
    ) {
        it.setObject(1, now.toDbTimestamp())
        1
    }

    /** Stores the new attempt count, the next try time and the error; false when our lease no longer matches. */
    fun scheduleRetry(claimed: ClaimedEvent, attempts: Int, nextAttemptAt: Instant, error: String): Boolean = update(
        "UPDATE outbox SET attempts = ?, next_attempt_at = ?, last_error = ? $FENCE",
        claimed,
    ) {
        it.setInt(1, attempts)
        it.setObject(2, nextAttemptAt.toDbTimestamp())
        it.setString(3, error.limitForColumn())
        3
    }

    /** Marks the row DEAD (no more retries) with the last error; false when our lease no longer matches. */
    fun markDead(claimed: ClaimedEvent, attempts: Int, error: String): Boolean = update(
        "UPDATE outbox SET status = 'DEAD', attempts = ?, last_error = ? $FENCE",
        claimed,
    ) {
        it.setInt(1, attempts)
        it.setString(2, error.limitForColumn())
        2
    }

    /** Moves the row to [nextAttemptAt] without touching `attempts` (circuit open, or released on shutdown). */
    fun reschedule(claimed: ClaimedEvent, nextAttemptAt: Instant): Boolean =
        update("UPDATE outbox SET next_attempt_at = ? $FENCE", claimed) {
            it.setObject(1, nextAttemptAt.toDbTimestamp())
            1
        }

    /** Count and age of the work still to do, for the pending and lag gauges. Served by the partial index. */
    fun pendingStats(): PendingStats = transaction { connection ->
        connection.prepareStatement(STATS_SQL).use { statement ->
            statement.executeQuery().use { rows ->
                rows.next()
                PendingStats(
                    count = rows.getLong(1),
                    oldestCreatedAt = rows.getObject(2, OffsetDateTime::class.java)?.toInstant(),
                )
            }
        }
    }

    /**
     * Runs a single fenced UPDATE. [bindSet] binds the SET clause from index 1 and returns how many parameters it
     * bound; the two fence parameters follow.
     * @return false when the fence did not match (row re-claimed by another instance or no longer PENDING).
     */
    private fun update(sql: String, claimed: ClaimedEvent, bindSet: (PreparedStatement) -> Int): Boolean =
        transaction { connection ->
            connection.prepareStatement(sql).use { statement ->
                val bound = bindSet(statement)
                statement.setObject(bound + 1, claimed.event.id)
                statement.setObject(bound + 2, claimed.leaseUntil.toDbTimestamp())
                statement.executeUpdate() == 1
            }
        }

    /**
     * Explicit commit instead of relying on the pool's auto-commit default, so the relay behaves the same whatever
     * the pool is configured with. Each call is one short transaction.
     */
    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    /** Maps a claimed row to a [ClaimedEvent] that carries our lease. */
    private fun ResultSet.toClaimedEvent(leaseUntil: Instant) = ClaimedEvent(
        event =
        OutboxEvent(
            id = getObject("id", UUID::class.java),
            aggregateId = getObject("aggregate_id", UUID::class.java),
            eventType = getString("event_type"),
            payload = getString("payload"),
            attempts = getInt("attempts"),
            requestId = getString("request_id"),
            traceparent = getString("traceparent"),
        ),
        leaseUntil = leaseUntil,
        createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )

    /** Cuts the error text to [MAX_ERROR_LENGTH] characters, ending with "…" when it was cut. */
    private fun String.limitForColumn() = if (length <= MAX_ERROR_LENGTH) this else take(MAX_ERROR_LENGTH - 1) + "…"

    private companion object {
        /**
         * Longest error text kept in `last_error`, in characters; longer text is cut by [limitForColumn]. The column
         * itself is TEXT (migration V1) and has no limit of its own.
         */
        const val MAX_ERROR_LENGTH = 500

        /**
         * The fence (see the class comment): the row must still be PENDING and still carry our lease in
         * `next_attempt_at`. Its two `?` are bound last, by [update].
         */
        const val FENCE = "WHERE id = ? AND status = 'PENDING' AND next_attempt_at = ?"

        // The CTE selects and locks the due rows (the partial index outbox_pending_idx serves WHERE + ORDER BY), the
        // outer UPDATE leases exactly those rows, and RETURNING hands them back in the same round trip.
        const val CLAIM_SQL =
            """
            WITH due AS (
                SELECT id FROM outbox
                WHERE status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY next_attempt_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE outbox o SET next_attempt_at = ?
            FROM due
            WHERE o.id = due.id
            RETURNING o.id, o.aggregate_id, o.event_type, o.payload::text AS payload, o.attempts, o.created_at,
                      o.request_id, o.traceparent
            """

        const val STATS_SQL = "SELECT count(*), min(created_at) FROM outbox WHERE status = 'PENDING'"
    }
}

/**
 * PostgreSQL stores microseconds; truncating here makes the value we write equal to the value we later compare
 * against (the lease fence), whatever precision the JVM clock has.
 */
internal fun Instant.toDbTimestamp(): OffsetDateTime = truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)
