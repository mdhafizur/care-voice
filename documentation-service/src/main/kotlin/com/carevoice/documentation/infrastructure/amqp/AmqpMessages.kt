package com.carevoice.documentation.infrastructure.amqp

import com.carevoice.contracts.ObservationRecordedEvent
import com.carevoice.documentation.outbox.EventEnvelope
import com.carevoice.documentation.outbox.OutboxEvent
import com.rabbitmq.client.AMQP
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant
import java.util.Date

/** One AMQP message ready for `basicPublish`: where it goes, its properties and the exact body bytes. */
class AmqpMessage(
    val exchange: String,
    val routingKey: String,
    val properties: AMQP.BasicProperties,
    val body: ByteArray,
)

/**
 * The single mapping from an outbox row to an AMQP message (docs/CONTRACTS.md, section 8a). The Pact message test
 * uses exactly this function, so the contract is checked against the bytes and headers production sends.
 *
 * Pattern: Mapper.
 */
object AmqpMessages {
    const val CONTENT_TYPE = "application/json"

    /**
     * AMQP 0-9-1 `delivery-mode` 2 = persistent: the broker writes the message to disk (1 would be transient, kept
     * in memory only). Official reference: https://www.rabbitmq.com/amqp-0-9-1-reference.html
     */
    const val PERSISTENT = 2

    const val HEADER_SCHEMA_VERSION = "schemaVersion"
    const val HEADER_REQUEST_ID = "requestId"
    const val HEADER_TRACEPARENT = "traceparent"

    /**
     * Routing key `<eventType>.v<schemaVersion>`, e.g. `observation.recorded.v1`: consumers bind with
     * `observation.recorded.*`, and a new schema version gets its own key so old and new consumers can coexist.
     * The outbox's `event_type` is the dotted event name ([ObservationRecordedEvent.TYPE]).
     */
    fun routingKey(event: OutboxEvent): String =
        "${event.eventType}.v${EventEnvelope.schemaVersionOf(event) ?: ObservationRecordedEvent.SCHEMA_VERSION}"

    /**
     * Builds the AMQP message for an outbox row: persistent delivery, `eventId` as message id, the correlation headers,
     * and the stored payload as the body, unchanged.
     */
    fun toMessage(event: OutboxEvent, clock: Clock = Clock.systemUTC()): AmqpMessage {
        // Step 1: the headers: schema version, and the request id and trace context when the event has them.
        val headers =
            buildMap<String, Any> {
                put(
                    HEADER_SCHEMA_VERSION,
                    EventEnvelope.schemaVersionOf(event) ?: ObservationRecordedEvent.SCHEMA_VERSION,
                )
                EventEnvelope.requestIdOf(event)?.let { put(HEADER_REQUEST_ID, it) }
                EventEnvelope.traceparentOf(event)?.let { put(HEADER_TRACEPARENT, it) }
            }
        // Step 2: the message properties: JSON, persistent (the broker keeps it on disk) and the event id as
        // message id.
        val properties =
            AMQP.BasicProperties
                .Builder()
                .contentType(CONTENT_TYPE)
                .contentEncoding("utf-8")
                .deliveryMode(PERSISTENT)
                .messageId(EventEnvelope.eventIdOf(event))
                .type(event.eventType)
                // The event's own time, so a redelivery carries the same timestamp as the first delivery.
                .timestamp(Date.from(occurredAtOf(event) ?: clock.instant()))
                .appId("documentation-service")
                .headers(headers)
                .build()
        // Step 3: put it together for the events exchange with the versioned routing key.
        // The payload was serialised once, with ContractJson, when the observation was committed; it is sent byte
        // for byte, never re-encoded, so HTTP and AMQP consumers see identical JSON.
        return AmqpMessage(
            exchange = AmqpEventPublisher.EVENTS_EXCHANGE,
            routingKey = routingKey(event),
            properties = properties,
            body = event.payload.toByteArray(Charsets.UTF_8),
        )
    }

    /** Reads `occurredAt` from the payload, or null when the payload has no readable timestamp. */
    private fun occurredAtOf(event: OutboxEvent): Instant? = runCatching {
        val value = (Json.parseToJsonElement(event.payload) as JsonObject)["occurredAt"] as JsonPrimitive
        Instant.parse(value.content)
    }.getOrNull()
}
