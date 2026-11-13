package com.carevoice.documentation.outbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Transport-independent facts about an [OutboxEvent] that every [EventPublisher] needs: the id the consumer
 * deduplicates on, the schema version, and the correlation values that are safe to put into a header.
 *
 * One implementation for the HTTP and the AMQP adapter, so both transports send exactly the same identifiers
 * (docs/CONTRACTS.md, section 8a: "both deliver the same ObservationRecordedEvent JSON").
 */
object EventEnvelope {
    /**
     * Same rule as the inbound `X-Request-Id` validation (docs/CONTRACTS.md, section 6): 1 to 64 characters, each a
     * letter, a digit, or one of `.` `_` `:` `-`.
     */
    private val REQUEST_ID = Regex("[A-Za-z0-9._:-]{1,64}")

    /**
     * W3C Trace Context `traceparent`: version-traceid-parentid-flags, lower-case hex. The parts are 2 hex digits
     * (version), 32 (trace id, 16 bytes), 16 (parent span id, 8 bytes) and 2 (flags).
     * Spec: https://www.w3.org/TR/trace-context/#traceparent-header
     */
    private val TRACEPARENT = Regex("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")

    /**
     * The event's own `eventId` (what alert-service deduplicates on). Falls back to the outbox row id, which the
     * writer sets to the same value, when the payload has no readable `eventId`.
     */
    fun eventIdOf(event: OutboxEvent): String =
        payloadField(event, "eventId")?.takeIf { it.isString && it.content.isNotBlank() }?.content
            ?: event.id.toString()

    /** `schemaVersion` of the payload, or null when the payload does not carry one. */
    fun schemaVersionOf(event: OutboxEvent): Int? =
        payloadField(event, "schemaVersion")?.takeIf { !it.isString }?.intOrNull

    /**
     * The stored request id, or null when absent or malformed. The value comes from our own database, but it ends up
     * in a header of another service, so it is validated anyway: nothing malformed may reach a header.
     */
    fun requestIdOf(event: OutboxEvent): String? = event.requestId?.takeIf { REQUEST_ID.matches(it) }

    /** The stored W3C `traceparent`, or null when absent or malformed (validated for the same reason). */
    fun traceparentOf(event: OutboxEvent): String? = event.traceparent?.takeIf { TRACEPARENT.matches(it) }

    /** One top-level field of the payload; null when the payload is not a JSON object or has no such field. */
    private fun payloadField(event: OutboxEvent, name: String): JsonPrimitive? =
        runCatching { (Json.parseToJsonElement(event.payload) as? JsonObject)?.get(name) as? JsonPrimitive }.getOrNull()
}
