package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.contracts.ContractJson
import com.carevoice.contracts.ObservationRecordedEvent
import com.carevoice.documentation.application.Correlation
import com.carevoice.documentation.application.EventOutbox
import com.carevoice.documentation.outbox.OutboxWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID

/**
 * Adapter of the [EventOutbox] port onto the outbox package's [OutboxWriter] (Transactional Outbox pattern).
 *
 * Must be called inside `TransactionRunner.inTransaction`: it uses that transaction's connection (and fails loudly
 * without one), so the event row is committed atomically with the observation. The payload is encoded with
 * [ContractJson], byte for byte what both transports (AMQP and HTTP) deliver; the outbox row id IS the event id.
 */
class PostgresEventOutbox(private val clock: Clock) : EventOutbox {
    /** Writes the event as an outbox row in the current transaction, with the request's correlation ids. */
    override suspend fun append(event: ObservationRecordedEvent, correlation: Correlation) {
        val connection = JdbcTransactions.currentConnection()
        val payload = ContractJson.encodeToString(ObservationRecordedEvent.serializer(), event)
        val now = clock.instant()
        withContext(Dispatchers.IO + MDCContext()) {
            OutboxWriter.insert(
                connection = connection,
                id = UUID.fromString(event.eventId),
                aggregateId = UUID.fromString(event.observationId),
                eventType = ObservationRecordedEvent.TYPE,
                payloadJson = payload,
                now = now,
                requestId = correlation.requestId,
                traceparent = correlation.traceparent,
            )
        }
    }
}
