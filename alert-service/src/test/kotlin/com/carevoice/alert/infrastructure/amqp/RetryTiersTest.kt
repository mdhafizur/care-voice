package com.carevoice.alert.infrastructure.amqp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@DisplayName("AMQP retry tiers and delivery counting")
class RetryTiersTest {
    @Test
    fun `should wait longer after every failed delivery and dead-letter after the last tier`() {
        assertThat(AmqpTopology.RETRY_DELAYS).containsExactly(5.seconds, 30.seconds, 2.minutes, 10.minutes, 30.minutes)
        assertThat((1..5).map { AmqpTopology.retryTier(it) }).containsExactly(0, 1, 2, 3, 4)
        assertThat(ObservationEventConsumer.MAX_DELIVERIES).isEqualTo(6)
    }

    @Test
    fun `should keep the original 5 s queue as the first tier and name the others by their delay`() {
        assertThat(AmqpTopology.retryQueue(0)).isEqualTo("alert-service.observations.retry")
        assertThat(AmqpTopology.retryRoutingKey(0)).isEqualTo(AmqpTopology.QUEUE_ROUTING_KEY)
        assertThat(AmqpTopology.retryQueue(4)).isEqualTo("alert-service.observations.retry.1800s")
        assertThat(AmqpTopology.retryRoutingKey(2)).isEqualTo("alert-service.observations.retry.120s")
    }

    @Test
    fun `should count the first delivery as attempt 1`() {
        assertThat(ObservationEventConsumer.deliveryAttempt(emptyMap())).isEqualTo(1)
    }

    @Test
    fun `should read the attempt header and add rejections of the main queue since then`() {
        val headers =
            mapOf(
                ObservationEventConsumer.HEADER_ATTEMPT to 3,
                "x-death" to
                    listOf(
                        mapOf("queue" to AmqpTopology.QUEUE, "reason" to "rejected", "count" to 1L),
                        mapOf("queue" to AmqpTopology.RETRY_QUEUE, "reason" to "expired", "count" to 1L),
                    ),
            )

        assertThat(ObservationEventConsumer.deliveryAttempt(headers)).isEqualTo(4)
    }

    @Test
    fun `should still count messages that only went through broker dead-lettering`() {
        val headers =
            mapOf("x-death" to listOf(mapOf("queue" to AmqpTopology.QUEUE, "reason" to "rejected", "count" to 2L)))

        assertThat(ObservationEventConsumer.deliveryAttempt(headers)).isEqualTo(3)
    }

    @Test
    fun `should drop the death history from a re-published copy`() {
        val headers =
            mapOf(
                "x-death" to emptyList<Any>(),
                "x-first-death-queue" to "q",
                "x-last-death-reason" to "rejected",
                "requestId" to "r-1",
            )

        assertThat(ObservationEventConsumer.withoutDeathHistory(headers)).containsOnlyKeys("requestId")
    }

    @Test
    fun `should reset retry state and count the replay`() {
        val headers =
            mapOf(
                ObservationEventConsumer.HEADER_ATTEMPT to 6,
                ObservationEventConsumer.HEADER_DEAD_LETTER_REASON to "max-deliveries-exceeded",
                ObservationEventConsumer.HEADER_ORIGINAL_ROUTING_KEY to "observation.recorded.v1",
                "x-death" to emptyList<Any>(),
                "schemaVersion" to 1,
                DeadLetterReplayer.HEADER_REPLAYED to 1,
            )

        assertThat(DeadLetterReplayer.replayHeaders(headers))
            .containsOnlyKeys("schemaVersion", DeadLetterReplayer.HEADER_REPLAYED)
            .containsEntry(DeadLetterReplayer.HEADER_REPLAYED, 2)
    }
}
