package com.carevoice.alert.application

import com.carevoice.contracts.ContractJson
import com.carevoice.contracts.ObservationRecordedEvent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Turns the raw bytes of an event (HTTP body or AMQP message) into an [ObservationRecordedEvent], identically for both
 * transports.
 *
 * The `schemaVersion` is checked BEFORE the full decode: a future version may change the shape so much that decoding
 * fails, and the caller must still learn "unsupported version" (422 / dead letter) rather than "malformed" (400).
 */
object EventParser {
    /**
     * @throws AlertServiceException.MalformedEvent when [body] is not a JSON object matching the event structure.
     * @throws AlertServiceException.UnsupportedSchemaVersion when the event declares a version this code cannot read.
     */
    fun parse(body: String): ObservationRecordedEvent {
        // Step 1: read the text into a JSON tree. Bad JSON, or JSON that is not an object, is a malformed event.
        val element =
            try {
                ContractJson.parseToJsonElement(body)
            } catch (_: SerializationException) {
                throw AlertServiceException.MalformedEvent("the body is not valid JSON")
            }
        if (element !is JsonObject) throw AlertServiceException.MalformedEvent("the body must be a JSON object")

        // Step 2: check schemaVersion before the full decode, because a newer version may have a shape that cannot
        // be decoded, and the caller must hear "unsupported version", not "malformed".
        val versionElement = element["schemaVersion"]
        if (versionElement != null) {
            val version =
                (versionElement as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                    ?: throw AlertServiceException.MalformedEvent("schemaVersion must be an integer")
            requireSupported(version)
        }
        // Step 3: decode the full event. A decode error becomes MalformedEvent with the first line of its message.
        return try {
            ContractJson.decodeFromJsonElement(ObservationRecordedEvent.serializer(), element)
        } catch (e: SerializationException) {
            throw malformedStructure(e)
        } catch (e: IllegalArgumentException) {
            throw malformedStructure(e)
        }
    }

    /** A MalformedEvent that carries the first line of the decode error's message (or "invalid structure"). */
    private fun malformedStructure(e: Exception): AlertServiceException.MalformedEvent =
        AlertServiceException.MalformedEvent(e.message?.lineSequence()?.firstOrNull() ?: "invalid structure")

    /** Also used by the AMQP consumer for the `schemaVersion` message header. */
    fun requireSupported(version: Int) {
        if (version !in ProcessObservationEvent.SUPPORTED_SCHEMA_VERSIONS) {
            val supported = ProcessObservationEvent.SUPPORTED_SCHEMA_VERSIONS
            throw AlertServiceException.UnsupportedSchemaVersion(version, supported)
        }
    }
}
