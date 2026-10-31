package com.carevoice.documentation.outbox

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Writes outbox rows. The caller passes the connection of its OWN open transaction, which is the whole point of the
 * transactional outbox pattern:
 *
 * The naive alternative, "commit the observation, then call alert-service", has a gap: if the process dies or the
 * call fails between the two steps, the observation exists but the alert never fires (or, the other way round, an
 * alert fires for an observation that was rolled back). Writing the event into the same database transaction as the
 * observation makes both atomic: either both are committed or neither is. The [OutboxRelay] delivers committed rows
 * afterwards, at least once.
 */
object OutboxWriter {
    private const val INSERT_SQL =
        """
        INSERT INTO outbox (id, aggregate_id, event_type, payload, status, attempts, next_attempt_at, created_at,
                            request_id, traceparent)
        VALUES (?, ?, ?, CAST(? AS jsonb), 'PENDING', 0, ?, ?, ?, ?)
        """

    /**
     * Inserts one PENDING row, due immediately. Does not commit; the caller's transaction decides.
     *
     * @param id the outbox row id. Using the event's own id here keeps one identifier for the event end to end.
     * @param now the caller's clock, so tests and the relay agree on time.
     * @param requestId the current request's `X-Request-Id`, so the later, asynchronous publish can be correlated.
     * @param traceparent the current W3C trace context, forwarded to alert-service when the event is published.
     */
    fun insert(
        connection: Connection,
        id: UUID,
        aggregateId: UUID,
        eventType: String,
        payloadJson: String,
        now: Instant,
        requestId: String? = null,
        traceparent: String? = null,
    ) {
        val timestamp = now.toDbTimestamp()
        connection.prepareStatement(INSERT_SQL).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, aggregateId)
            statement.setString(3, eventType)
            statement.setString(4, payloadJson)
            statement.setObject(5, timestamp)
            statement.setObject(6, timestamp)
            statement.setString(7, requestId)
            statement.setString(8, traceparent)
            statement.executeUpdate()
        }
    }
}
