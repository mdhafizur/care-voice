package com.carevoice.alert.observability

import com.carevoice.alert.application.AlertMetrics
import com.carevoice.alert.domain.Alert
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry

/**
 * Adapter: business metrics of docs/CONTRACTS.md, section 10, on Micrometer. Counters are summed across pods in
 * dashboards (`sum()`), so every pod reports only what it did itself.
 *
 * - `carevoice_alerts_total{rule,severity}` — alerts actually inserted (duplicates are not counted again).
 * - `carevoice_events_processed_total{result=processed|duplicate}` — events by outcome, both transports.
 */
class MicrometerAlertMetrics(private val registry: MeterRegistry) : AlertMetrics {
    override fun alertsCreated(alerts: List<Alert>) {
        alerts.forEach { alert ->
            Counter
                .builder("carevoice.alerts")
                .description("Alerts created")
                .tag("rule", alert.rule.name)
                .tag("severity", alert.severity.name)
                .register(registry)
                .increment()
        }
    }

    override fun eventProcessed(duplicate: Boolean) {
        Counter
            .builder("carevoice.events.processed")
            .description("Observation events processed")
            .tag("result", if (duplicate) "duplicate" else "processed")
            .register(registry)
            .increment()
    }
}
