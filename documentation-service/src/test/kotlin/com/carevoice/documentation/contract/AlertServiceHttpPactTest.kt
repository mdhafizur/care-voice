package com.carevoice.documentation.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.carevoice.documentation.infrastructure.alert.AlertServiceClient
import com.carevoice.documentation.infrastructure.http.HttpClients
import com.carevoice.documentation.outbox.OutboxTestFixtures
import com.carevoice.documentation.outbox.PublishResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer side of the HTTP contract with alert-service (docs/CONTRACTS.md, sections 8, 9 and 12).
 *
 * The interaction is exercised through the production [AlertServiceClient], so the pact records what the client
 * really sends (headers, the ContractJson body) rather than what a hand-written request claims it sends.
 * alert-service verifies the pact file as the provider. V4 so the HTTP and the message interaction can live in the
 * same pact file. Flat class (no `@Nested`): the Pact extension looks up `@Pact` methods and writes the pact per
 * test class.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = PactParticipants.PROVIDER,
    providerType = ProviderType.SYNCH,
    pactVersion = PactSpecVersion.V4,
)
@DisplayName("Pact: documentation-service -> alert-service (HTTP)")
class AlertServiceHttpPactTest {
    @Pact(consumer = PactParticipants.CONSUMER, provider = PactParticipants.PROVIDER)
    fun observationRecordedEvent(builder: PactDslWithProvider): V4Pact = builder
        .given("the alert service accepts events")
        .uponReceiving("an observation recorded event")
        .method("POST")
        .path(AlertServiceClient.PATH)
        .matchHeader("Authorization", BEARER_REGEX, "Bearer header.payload.signature")
        .matchHeader("Idempotency-Key", UUID_REGEX, EVENT_ID)
        // Declared before body(): Pact would otherwise expect "application/json; charset=UTF-8" exactly.
        .matchHeader("Content-Type", "application/json(;\\s*charset=[\\w-]+)?", "application/json")
        .body(PactBodies.observationRecordedEvent())
        .willRespondWith()
        .status(202)
        .body(
            PactDslJsonBody()
                .uuid("eventId", EVENT_ID)
                .integerType("alertsCreated", 1L),
        ).toPact(V4Pact::class.java)

    @Test
    fun `should publish an observation recorded event that alert-service accepts`(mockServer: MockServer) {
        HttpClients.create().use { httpClient ->
            val client =
                AlertServiceClient(
                    baseUrl = mockServer.getUrl(),
                    httpClient = httpClient,
                    serviceToken = { "header.payload.signature" },
                    meterRegistry = SimpleMeterRegistry(),
                )

            val result = runBlocking { client.publish(OutboxTestFixtures.outboxEvent(id = UUID.fromString(EVENT_ID))) }

            assertThat(result).isEqualTo(PublishResult.Published)
        }
    }

    private companion object {
        const val EVENT_ID = PactBodies.EVENT_ID
        const val UUID_REGEX = PactBodies.UUID_REGEX

        /** Any compact JWS: the provider verifies with a token it mints itself. */
        const val BEARER_REGEX = "Bearer [A-Za-z0-9_=-]+\\.[A-Za-z0-9_=-]+\\.[A-Za-z0-9_.+/=-]*"
    }
}
