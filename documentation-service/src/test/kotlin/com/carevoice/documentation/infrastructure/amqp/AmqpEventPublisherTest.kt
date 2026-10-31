package com.carevoice.documentation.infrastructure.amqp

import com.carevoice.contracts.ObservationRecordedEvent
import com.carevoice.documentation.outbox.OutboxTestFixtures
import com.carevoice.documentation.outbox.PublishResult
import com.carevoice.testsupport.TestDatabases
import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@DisplayName("AmqpEventPublisher")
class AmqpEventPublisherTest {
    private val meterRegistry = SimpleMeterRegistry()

    private fun publishCount(result: String): Double =
        meterRegistry.find("carevoice.amqp.publish").tag("result", result).counter()?.count() ?: 0.0

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @Test
        fun `should return Retryable when the broker is unreachable`() {
            val publisher =
                AmqpEventPublisher("amqp://guest:guest@127.0.0.1:1", meterRegistry, confirmTimeout = 1.seconds)
            try {
                val result = runBlocking { publisher.publish(OutboxTestFixtures.outboxEvent()) }
                assertThat(result).isInstanceOf(PublishResult.Retryable::class.java)
                assertThat((result as PublishResult.Retryable).reason).startsWith("broker unreachable")
                assertThat(publisher.isConnected).isFalse()
                assertThat(publishCount("retry")).isEqualTo(1.0)
            } finally {
                runBlocking { publisher.close() }
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Against RabbitMQ")
    inner class AgainstRabbitMq {
        private lateinit var connection: Connection
        private lateinit var channel: Channel
        private lateinit var publisher: AmqpEventPublisher
        private val queue = "test.observations.${UUID.randomUUID()}"

        @BeforeAll
        fun connect() {
            assumeTrue(TestDatabases.dockerAvailable, "Docker is not available; RabbitMQ tests are skipped")
            connection = ConnectionFactory().apply { setUri(TestDatabases.rabbit.amqpUri) }.newConnection("test")
            channel = connection.createChannel()
            channel.exchangeDeclare(AmqpEventPublisher.EVENTS_EXCHANGE, BuiltinExchangeType.TOPIC, true)
            channel.queueDeclare(queue, false, false, true, null)
            channel.queueBind(queue, AmqpEventPublisher.EVENTS_EXCHANGE, "observation.recorded.*")
            // A queue that rejects every message, so the broker answers the publish with a nack.
            channel.queueDeclare(
                REJECTING_QUEUE,
                false,
                false,
                true,
                mapOf(
                    "x-max-length" to 0,
                    "x-overflow" to "reject-publish",
                ),
            )
            channel.queueBind(REJECTING_QUEUE, AmqpEventPublisher.EVENTS_EXCHANGE, "$REJECTED_TYPE.v1")
        }

        @AfterAll
        fun disconnect() {
            if (::connection.isInitialized) connection.close()
        }

        @BeforeEach
        fun createPublisher() {
            channel.queuePurge(queue)
            publisher = AmqpEventPublisher(TestDatabases.rabbit.amqpUri, meterRegistry, confirmTimeout = 5.seconds)
        }

        @AfterEach
        fun closePublisher() {
            runBlocking { publisher.close() }
        }

        @Test
        fun `should publish a persistent JSON message with id, type, timestamp and correlation headers`() {
            val event = OutboxTestFixtures.outboxEvent()

            val result = runBlocking { publisher.publish(event) }

            assertThat(result).isEqualTo(PublishResult.Published)
            // Published means the broker confirmed it, so it is already in the queue.
            val delivery = channel.basicGet(queue, true)
            assertThat(delivery).isNotNull()
            assertThat(delivery.envelope.exchange).isEqualTo("carevoice.events")
            assertThat(delivery.envelope.routingKey).isEqualTo("observation.recorded.v1")
            assertThat(String(delivery.body, Charsets.UTF_8)).isEqualTo(event.payload)
            val properties = delivery.props
            assertThat(properties.contentType).isEqualTo("application/json")
            assertThat(properties.deliveryMode).isEqualTo(2)
            assertThat(properties.messageId).isEqualTo(event.id.toString())
            assertThat(properties.type).isEqualTo(ObservationRecordedEvent.TYPE)
            assertThat(properties.timestamp.toInstant()).isEqualTo(OutboxTestFixtures.FIXED_NOW)
            assertThat(properties.headers["schemaVersion"]).isEqualTo(1)
            assertThat(properties.headers["requestId"].toString()).isEqualTo(OutboxTestFixtures.REQUEST_ID)
            assertThat(properties.headers["traceparent"].toString()).isEqualTo(OutboxTestFixtures.TRACEPARENT)
            assertThat(publishCount("published")).isGreaterThanOrEqualTo(1.0)
            assertThat(publisher.isConnected).isTrue()
        }

        @Test
        fun `should omit correlation headers the outbox row does not have`() {
            runBlocking { publisher.publish(OutboxTestFixtures.outboxEvent(requestId = null, traceparent = null)) }

            val headers = channel.basicGet(queue, true).props.headers
            assertThat(headers).containsOnlyKeys("schemaVersion")
        }

        @Test
        fun `should return Retryable when no queue is bound for the routing key`() {
            val result = runBlocking { publisher.publish(OutboxTestFixtures.outboxEvent(eventType = "unbound.event")) }

            assertThat(result).isInstanceOf(PublishResult.Retryable::class.java)
            assertThat((result as PublishResult.Retryable).reason).startsWith("unroutable: 312")
        }

        @Test
        fun `should return Retryable when the broker nacks the message`() {
            val result = runBlocking { publisher.publish(OutboxTestFixtures.outboxEvent(eventType = REJECTED_TYPE)) }

            assertThat(result).isEqualTo(PublishResult.Retryable("nacked by broker"))
        }

        @Test
        fun `should keep publishing after a failed publish on the same publisher`() {
            runBlocking { publisher.publish(OutboxTestFixtures.outboxEvent(eventType = "unbound.event")) }

            assertThat(
                runBlocking {
                    publisher.publish(OutboxTestFixtures.outboxEvent())
                },
            ).isEqualTo(PublishResult.Published)
        }

        @Test
        fun `should confirm every message when many coroutines publish concurrently`() {
            val events = List(64) { OutboxTestFixtures.outboxEvent() }

            val results = runBlocking(Dispatchers.Default) { events.map { async { publisher.publish(it) } }.awaitAll() }

            assertThat(results).containsOnly(PublishResult.Published)
            val received = generateSequence { channel.basicGet(queue, true) }.map { it.props.messageId }.toList()
            assertThat(received).containsExactlyInAnyOrderElementsOf(events.map { it.id.toString() })
        }

        @Test
        fun `should declare the exchanges of the contract topology`() {
            runBlocking { publisher.publish(OutboxTestFixtures.outboxEvent()) }

            // Passive declarations fail (and close the channel) when the exchange does not exist.
            connection.createChannel().use { probe ->
                probe.exchangeDeclarePassive("carevoice.events")
                probe.exchangeDeclarePassive("carevoice.events.retry")
                probe.exchangeDeclarePassive("carevoice.events.dlx")
            }
        }
    }

    private companion object {
        const val REJECTED_TYPE = "rejected.event"
        val REJECTING_QUEUE = "test.rejecting.${UUID.randomUUID()}"
    }
}
