package com.carevoice.documentation.infrastructure.amqp

import com.carevoice.documentation.outbox.OutboxTestFixtures
import com.carevoice.documentation.outbox.PublishResult
import com.carevoice.documentation.outbox.awaitUntil
import com.carevoice.testsupport.TestDatabases
import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.ConnectionFactory
import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.ToxicDirection
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.Network
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.containers.ToxiproxyContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.seconds

/**
 * Network faults between documentation-service and RabbitMQ, injected with Toxiproxy: the broker is reachable only
 * through a proxy whose behaviour the test changes (added latency, dropped connection). The point is to show that
 * every fault ends as a [PublishResult.Retryable] (so the outbox keeps the event) and that the publisher recovers
 * by itself once the network does.
 */
@Tag("fault-injection")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AmqpEventPublisher under network faults (Toxiproxy)")
class AmqpFaultInjectionTest {
    private lateinit var network: Network
    private lateinit var rabbit: RabbitMQContainer
    private lateinit var toxiproxy: ToxiproxyContainer
    private lateinit var proxy: Proxy
    private lateinit var publisher: AmqpEventPublisher
    private val meterRegistry = SimpleMeterRegistry()

    @BeforeAll
    fun startContainers() {
        assumeTrue(TestDatabases.dockerAvailable, "Docker is not available; fault-injection tests are skipped")
        network = Network.newNetwork()
        rabbit =
            RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine"))
                .withNetwork(network)
                .withNetworkAliases(RABBIT_ALIAS)
                .also { it.start() }
        toxiproxy =
            ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
                .withNetwork(network)
                .also { it.start() }
        proxy =
            ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort)
                .createProxy("rabbitmq", "0.0.0.0:$PROXY_PORT", "$RABBIT_ALIAS:5672")
        bindQueue()
    }

    @AfterAll
    fun stopContainers() {
        if (::toxiproxy.isInitialized) toxiproxy.stop()
        if (::rabbit.isInitialized) rabbit.stop()
        if (::network.isInitialized) network.close()
    }

    @BeforeEach
    fun createPublisher() {
        val uri = "amqp://${rabbit.adminUsername}:${rabbit.adminPassword}@${toxiproxy.host}:${toxiproxy.getMappedPort(
            PROXY_PORT,
        )}"
        publisher = AmqpEventPublisher(uri, meterRegistry, confirmTimeout = 1.seconds)
    }

    @AfterEach
    fun resetFaults() {
        // Network first, so closing the publisher is not slowed down by a toxic of the previous test.
        proxy.enable()
        proxy.toxics().all.forEach { it.remove() }
        runBlocking { publisher.close() }
    }

    /**
     * Messages need a bound queue, or they are returned as unroutable. Declared over a direct connection (not through
     * the proxy), the way the consumer would.
     */
    private fun bindQueue() {
        ConnectionFactory().apply {
            setUri(rabbit.amqpUrl.replace("amqp://", "amqp://${rabbit.adminUsername}:${rabbit.adminPassword}@"))
        }
            .newConnection("fault-test-setup")
            .use { connection ->
                connection.createChannel().use { channel ->
                    channel.exchangeDeclare(AmqpEventPublisher.EVENTS_EXCHANGE, BuiltinExchangeType.TOPIC, true)
                    channel.queueDeclare(QUEUE, false, false, false, null)
                    channel.queueBind(QUEUE, AmqpEventPublisher.EVENTS_EXCHANGE, "observation.recorded.*")
                }
            }
    }

    private fun publish(): PublishResult = runBlocking { publisher.publish(OutboxTestFixtures.outboxEvent()) }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should return Retryable when confirms are slower than the confirm timeout`() {
            assertThat(publish()).isEqualTo(PublishResult.Published)
            proxy.toxics().latency("slow-broker", ToxicDirection.DOWNSTREAM, 3_000)

            val result = publish()

            assertThat(result).isInstanceOf(PublishResult.Retryable::class.java)
        }

        @Test
        fun `should return Retryable while the broker is cut off and publish again after the network recovers`() {
            awaitUntil("a publish through the proxy succeeds") { publish() == PublishResult.Published }
            proxy.disable()

            assertThat(publish()).isInstanceOf(PublishResult.Retryable::class.java)

            proxy.enable()
            awaitUntil("the publisher has recovered", 30.seconds) { publish() == PublishResult.Published }
        }
    }

    private companion object {
        const val RABBIT_ALIAS = "rabbitmq"
        const val PROXY_PORT = 8666
        const val QUEUE = "fault.test"
    }
}
