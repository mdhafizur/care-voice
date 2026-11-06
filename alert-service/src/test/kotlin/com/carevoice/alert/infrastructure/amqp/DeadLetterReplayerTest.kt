package com.carevoice.alert.infrastructure.amqp

import com.carevoice.testsupport.TestDatabases
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Retry tiers and dead-letter replay against RabbitMQ")
class DeadLetterReplayerTest {
    private lateinit var factory: ConnectionFactory
    private lateinit var connection: Connection
    private lateinit var channel: Channel

    @BeforeAll
    fun connect() {
        assumeTrue(TestDatabases.dockerAvailable, "Docker is not available; RabbitMQ tests are skipped")
        factory = ConnectionFactory().apply { setUri(TestDatabases.rabbit.amqpUri) }
        connection = factory.newConnection("test")
        channel = connection.createChannel()
        AmqpTopology.declare(channel)
    }

    @AfterAll
    fun disconnect() {
        if (::connection.isInitialized) connection.close()
    }

    @BeforeEach
    fun purge() {
        val queues =
            listOf(AmqpTopology.QUEUE, AmqpTopology.DEAD_LETTER_QUEUE) +
                AmqpTopology.RETRY_DELAYS.indices.map { AmqpTopology.retryQueue(it) }
        queues.forEach { channel.queuePurge(it) }
    }

    @Test
    fun `should declare one retry queue per tier and route each tier's key to it`() {
        AmqpTopology.RETRY_DELAYS.indices.forEach { tier ->
            channel.basicPublish(AmqpTopology.RETRY_EXCHANGE, AmqpTopology.retryRoutingKey(tier), null, byteArrayOf(1))
        }

        AmqpTopology.RETRY_DELAYS.indices.forEach { tier ->
            assertThat(awaitCount(AmqpTopology.retryQueue(tier), 1)).describedAs("tier $tier").isEqualTo(1)
        }
        // Before the 5 s tier expires its message back into the main queue, where the next test would find it.
        purge()
    }

    @Test
    fun `should move dead-lettered events back to the queue with their retry state reset`() {
        val properties =
            AMQP.BasicProperties
                .Builder()
                .messageId("event-1")
                .headers(
                    mapOf(
                        "schemaVersion" to 1,
                        ObservationEventConsumer.HEADER_ATTEMPT to 6,
                        ObservationEventConsumer.HEADER_DEAD_LETTER_REASON to "max-deliveries-exceeded: X",
                    ),
                ).build()
        repeat(2) {
            channel.basicPublish(
                AmqpTopology.DEAD_LETTER_EXCHANGE,
                AmqpTopology.QUEUE_ROUTING_KEY,
                properties,
                "{}".toByteArray(),
            )
        }
        assertThat(awaitCount(AmqpTopology.DEAD_LETTER_QUEUE, 2)).isEqualTo(2)

        val result = DeadLetterReplayer(factory).replay(limit = 1)

        assertThat(result).isEqualTo(DeadLetterReplayer.Result(replayed = 1, remaining = 1))
        val replayed = channel.basicGet(AmqpTopology.QUEUE, true)
        assertThat(replayed).isNotNull
        assertThat(replayed.props.messageId).isEqualTo("event-1")
        assertThat(replayed.envelope.routingKey).isEqualTo(AmqpTopology.RETRY_ROUTING_KEY)
        assertThat(replayed.props.headers)
            .doesNotContainKeys(
                ObservationEventConsumer.HEADER_ATTEMPT,
                ObservationEventConsumer.HEADER_DEAD_LETTER_REASON,
            ).containsEntry(DeadLetterReplayer.HEADER_REPLAYED, 1)
        assertThat(ObservationEventConsumer.deliveryAttempt(replayed.props.headers)).isEqualTo(1)
    }

    /** Message counts of quorum queues are reported asynchronously; waits up to 5 s for [expected]. */
    private fun awaitCount(queue: String, expected: Int): Int {
        val deadline = System.nanoTime() + 5_000_000_000L
        var count = channel.queueDeclarePassive(queue).messageCount
        while (count < expected && System.nanoTime() < deadline) {
            Thread.sleep(50)
            count = channel.queueDeclarePassive(queue).messageCount
        }
        return count
    }
}
