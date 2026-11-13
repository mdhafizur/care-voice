package com.carevoice.documentation.resilience

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.time.Duration

/**
 * Latency timers for outbound calls, shaped for percentiles across instances.
 *
 * Why a histogram (`publishPercentileHistogram()`) instead of client-side percentiles (`publishPercentiles(0.95)`):
 * a percentile computed inside one JVM cannot be combined with the percentiles of other instances; averaging two
 * p95 values is mathematically meaningless. Histogram buckets are plain counters, so Prometheus can sum them over all
 * instances and compute `histogram_quantile(0.95, sum by (le) (rate(..._bucket[5m])))` for the whole service. The
 * buckets also carry exemplars (trace ids from the OpenTelemetry agent), so a slow bucket in Grafana links to a trace.
 *
 * The SLO buckets add exact boundaries at the thresholds we reason about: 100 ms (fast), 300 ms (the service SLO),
 * 1 s and 2 s (the outbound client timeout), so "share of calls under 300 ms" is an exact counter ratio, not an
 * interpolation between generated buckets.
 */
object LatencyTimers {
    val SLO_BUCKETS: Array<Duration> =
        arrayOf(Duration.ofMillis(100), Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofSeconds(2))

    /**
     * Registers a timer that publishes histogram buckets (with [SLO_BUCKETS] added) from 1 ms to 30 s,
     * so percentiles can be computed over all instances together.
     */
    fun timer(registry: MeterRegistry, name: String, description: String, tags: Tags): Timer = Timer
        .builder(name)
        .description(description)
        .tags(tags)
        .publishPercentileHistogram()
        .serviceLevelObjectives(*SLO_BUCKETS)
        // Bounds the generated bucket set to the range that matters, which keeps the series count small.
        .minimumExpectedValue(Duration.ofMillis(1))
        .maximumExpectedValue(Duration.ofSeconds(30))
        .register(registry)
}
