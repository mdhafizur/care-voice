package com.carevoice.documentation.contract

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.carevoice.contracts.ContractJson
import com.carevoice.contracts.ObservationRecordedEvent
import com.carevoice.documentation.infrastructure.amqp.AmqpMessages
import com.carevoice.documentation.outbox.OutboxTestFixtures
import com.carevoice.testsupport.assertJsonEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer side of the RabbitMQ contract (docs/CONTRACTS.md, section 8a): the message alert-service's consumer must
 * be able to process.
 *
 * The test checks that the message production builds ([AmqpMessages.toMessage], the function
 * [com.carevoice.documentation.infrastructure.amqp.AmqpEventPublisher] sends) satisfies the pact: the body bytes
 * must be accepted by the pact's matchers and the metadata must agree with the AMQP properties. A change to the
 * serializer or to the message properties therefore fails here before it can reach the provider.
 *
 * Flat class (no `@Nested`): the Pact extension collects and writes the pact per test class.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = PactParticipants.PROVIDER,
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V4,
)
@DisplayName("Pact: documentation-service -> alert-service (RabbitMQ message)")
class AlertServiceMessagePactTest {
    @Pact(consumer = PactParticipants.CONSUMER, provider = PactParticipants.PROVIDER)
    fun observationRecordedMessage(builder: MessagePactBuilder): V4Pact = builder
        .given("the alert service accepts events")
        .expectsToReceive("an observation recorded event message")
        .withMetadata(
            mapOf(
                "contentType" to AmqpMessages.CONTENT_TYPE,
                "schemaVersion" to ObservationRecordedEvent.SCHEMA_VERSION,
            ),
        ).withContent(PactBodies.observationRecordedEvent())
        .toPact(V4Pact::class.java)

    @Test
    fun `should produce a message that matches the pact`(message: V4Interaction.AsynchronousMessage) {
        val event = OutboxTestFixtures.outboxEvent(id = UUID.fromString(PactBodies.EVENT_ID))
        val produced = AmqpMessages.toMessage(event)

        val producedBody = String(produced.body, Charsets.UTF_8)
        val pactBody = message.contents.contents.valueAsString()

        // The produced body has exactly the pact's fields (no missing, no extra ones), and it is a valid event.
        assertJsonEquals(pactBody.withValuesOf(producedBody), producedBody)
        assertThat(ContractJson.decodeFromString(ObservationRecordedEvent.serializer(), producedBody).eventId)
            .isEqualTo(PactBodies.EVENT_ID)
        assertThat(message.contents.metadata["contentType"]).isEqualTo(produced.properties.contentType)
        assertThat(message.contents.metadata["schemaVersion"]).isEqualTo(produced.properties.headers["schemaVersion"])
    }

    /**
     * The pact's example values differ from the produced ones (type matchers), so the comparison is structural: take
     * the pact's field set and fill in the produced values. [assertJsonEquals] then fails on any missing or extra field.
     */
    private fun String.withValuesOf(produced: String): String {
        val expected = Json.parseToJsonElement(this)
        val actual = Json.parseToJsonElement(produced)
        return mergeKeys(expected, actual).toString()
    }

    private fun mergeKeys(expected: JsonElement, actual: JsonElement): JsonElement =
        if (expected is JsonObject && actual is JsonObject) {
            JsonObject(
                expected.mapValues { (key, value) -> actual[key]?.let { mergeKeys(value, it) } ?: value },
            )
        } else {
            actual
        }
}
