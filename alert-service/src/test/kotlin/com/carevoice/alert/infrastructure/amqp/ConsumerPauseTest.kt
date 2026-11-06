package com.carevoice.alert.infrastructure.amqp

import com.carevoice.alert.application.AlertServiceException
import com.carevoice.alert.application.ProcessObservationEvent
import com.carevoice.alert.application.ProcessingResult
import com.carevoice.alert.config.RabbitMqConfig
import com.carevoice.alert.support.Events
import com.carevoice.alert.support.SwitchableHealthCheck
import com.carevoice.testsupport.TestDatabases
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AMQP consumer pauses while MongoDB is down")
class ConsumerPauseTest {
    private lateinit var connection: Connection
    private lateinit var channel: Channel

    @BeforeAll
    fun connect() {
        assumeTrue(TestDatabases.dockerAvailable, "Docker is not available; RabbitMQ tests are skipped")
        connection = ConnectionFactory().apply { setUri(TestDatabases.rabbit.amqpUri) }.newConnection("test")
        channel = connection.createChannel()
        AmqpTopology.declare(channel)
    }

    @AfterAll
    fun disconnect() {
        if (::connection.isInitialized) connection.close()
    }

    @Test
    fun `should leave events in the queue without using a retry and resume once Mongo is back`() {
        val queues =
            listOf(AmqpTopology.QUEUE, AmqpTopology.DEAD_LETTER_QUEUE) +
                AmqpTopology.RETRY_DELAYS.indices.map { AmqpTopology.retryQueue(it) }
        queues.forEach { channel.queuePurge(it) }
        val mongo = SwitchableHealthCheck("mongo", healthy = false)
        val useCase = mockk<ProcessObservationEvent>()
        coEvery { useCase.execute(any()) } throws
            AlertServiceException.DependencyUnavailable("MongoDB", IllegalStateException("no primary"))
        val registry = SimpleMeterRegistry()
        val consumer =
            ObservationEventConsumer(
                RabbitMqConfig(TestDatabases.rabbit.amqpUri, prefetch = 4),
                useCase,
                registry,
                workers = 2,
                pauseWhileDown = mongo,
                healthPollInterval = 100.milliseconds,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            consumer.start(scope)
            awaitTrue("consumer attached") { runBlocking { consumer.isHealthy() } }
            val event = Events.event()
            channel.basicPublish(
                AmqpTopology.EVENTS_EXCHANGE,
                "observation.recorded.v1",
                AMQP.BasicProperties.Builder().messageId(event.eventId).build(),
                Events.json(event).toByteArray(),
            )

            awaitTrue("consumption paused") { gauge(registry) == 1.0 }
            awaitTrue("event back in the queue") { channel.queueDeclarePassive(AmqpTopology.QUEUE).messageCount == 1 }
            assertThat(AmqpTopology.RETRY_DELAYS.indices.sumOf { messages(AmqpTopology.retryQueue(it)) }).isZero()
            assertThat(messages(AmqpTopology.DEAD_LETTER_QUEUE)).isZero()
            // Paused on purpose: the consumer still reports itself ready (readiness is DOWN through the Mongo check).
            assertThat(runBlocking { consumer.isHealthy() }).isTrue()

            coEvery { useCase.execute(any()) } returns ProcessingResult(event.eventId, 1, duplicate = false)
            mongo.healthy = true

            awaitTrue("consumption resumed") { gauge(registry) == 0.0 }
            awaitTrue("event processed") { processed(registry) == 1.0 }
            assertThat(messages(AmqpTopology.QUEUE)).isZero()
        } finally {
            runBlocking { consumer.stop() }
            scope.cancel()
        }
    }

    private fun messages(queue: String): Int = channel.queueDeclarePassive(queue).messageCount

    private fun gauge(registry: SimpleMeterRegistry): Double? =
        registry.find("carevoice.amqp.consumer.paused").gauge()?.value()

    private fun processed(registry: SimpleMeterRegistry): Double =
        registry.find("carevoice.amqp.consume").tag("result", "processed").counter()?.count() ?: 0.0

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for: $what" }
            Thread.sleep(50)
        }
    }
}
