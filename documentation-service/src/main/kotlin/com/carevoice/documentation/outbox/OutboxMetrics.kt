package com.carevoice.documentation.outbox

import com.carevoice.documentation.resilience.LatencyTimers
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** What happened to one claimed row in the end; [tag] is the `result` tag of the publish metrics. */
internal enum class PublishOutcome(val tag: String) {
    PUBLISHED("published"),
    RETRY("retry"),
    DEAD("dead"),
    CIRCUIT_OPEN("circuit_open"),
}

/**
 * The meters of the [OutboxRelay]: two gauges for the backlog, plus one counter and one latency timer per
 * [PublishOutcome]. The metric names are the constants in the relay's companion object.
 */
internal class OutboxMetrics(meterRegistry: MeterRegistry) {
    /** Outbox rows waiting to be published; the relay's stats loop sets it. */
    val pending = AtomicLong(0)

    /** Age in seconds of the oldest pending outbox row; the relay's stats loop sets it. */
    val lagSeconds = AtomicLong(0)

    private val counters: Map<PublishOutcome, Counter>
    private val timers: Map<PublishOutcome, Timer>

    init {
        // Both gauges describe SHARED state (the one outbox table), so every replica reports the same value. Dashboards
        // and alerts must aggregate them with max(), never sum(), or the backlog would look N times larger than it is.
        // Counters and timers below are per replica and are summed (docs/CONTRACTS.md, section 9b).
        Gauge
            .builder(OutboxRelay.PENDING_METRIC, pending) { it.get().toDouble() }
            .description("Outbox rows waiting to be published")
            .register(meterRegistry)
        Gauge
            .builder(OutboxRelay.LAG_METRIC, lagSeconds) { it.get().toDouble() }
            .description("Age of the oldest pending outbox row")
            .baseUnit("seconds")
            .register(meterRegistry)
        // Registered up front so every result series exists (as 0) before the first event: rate() and alerting
        // rules behave better on a series that exists than on one that appears with the first failure.
        counters =
            PublishOutcome.entries.associateWith {
                Counter
                    .builder(OutboxRelay.PUBLISH_TOTAL_METRIC)
                    .description("Outbox publish attempts by result")
                    .tag("result", it.tag)
                    .register(meterRegistry)
            }
        timers =
            PublishOutcome.entries.associateWith {
                LatencyTimers.timer(
                    meterRegistry,
                    OutboxRelay.PUBLISH_SECONDS_METRIC,
                    "Duration of one outbox publish call",
                    Tags.of("result", it.tag),
                )
            }
    }

    /** Counts one finished publish under its [outcome] and records how long the publish call took. */
    fun recordPublish(outcome: PublishOutcome, elapsed: Duration) {
        counters.getValue(outcome).increment()
        timers.getValue(outcome).record(elapsed.toJavaDuration())
    }
}
