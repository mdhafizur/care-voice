package com.carevoice.documentation.contract

import au.com.dius.pact.consumer.dsl.PactDslJsonBody

/** Names both pact tests use, so the HTTP and the message interaction end up in the same pact file. */
object PactParticipants {
    const val CONSUMER = "documentation-service"
    const val PROVIDER = "alert-service"
}

/**
 * The `ObservationRecordedEvent` body with type matchers: alert-service must accept any event of this shape, not just
 * the example values. Fields are matched by type (and format where the contract defines one: UUIDs, ISO instants,
 * enum values), so a provider change that renames, retypes or drops a field breaks verification.
 */
object PactBodies {
    const val EVENT_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    const val OBSERVATION_ID = "6f1c2b8e-1d4a-4c55-9b7e-0a2f1e3d4c5b"
    const val UUID_REGEX = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private const val INSTANT_REGEX = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z"

    fun observationRecordedEvent(): PactDslJsonBody = PactDslJsonBody()
        .uuid("eventId", EVENT_ID)
        .integerType("schemaVersion", 1L)
        .stringMatcher("occurredAt", INSTANT_REGEX, "2026-10-25T10:00:00Z")
        .stringType("tenantId", "sunrise-care")
        .stringType("wardId", "ward-a")
        .stringType("residentId", "res-1")
        .uuid("observationId", OBSERVATION_ID)
        .`object`("vital")
        .stringValue("type", "BLOOD_PRESSURE")
        .integerType("systolic", 182L)
        .integerType("diastolic", 95L)
        .closeObject()!!
        .let { it as PactDslJsonBody }
        .stringMatcher("source", "RULES|AI", "RULES")
        .stringMatcher("status", "CONFIRMED|NEEDS_REVIEW", "CONFIRMED")
}
