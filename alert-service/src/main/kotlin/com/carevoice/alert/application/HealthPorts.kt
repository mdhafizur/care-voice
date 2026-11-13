package com.carevoice.alert.application

/**
 * Port for one dependency the readiness probe checks (MongoDB primary, RabbitMQ consumer). Adapters live in
 * `infrastructure/`, so the API layer can report readiness without importing a driver.
 */
interface DependencyHealthCheck {
    /** Key in the readiness body, e.g. `mongo` or `rabbitmq`. */
    val name: String

    /**
     * True when the dependency is usable right now; must return quickly. May throw: [CheckReadiness] counts that as
     * DOWN and logs the cause once.
     */
    suspend fun isHealthy(): Boolean
}

enum class HealthState { UP, DOWN }

data class ReadinessReport(val ready: Boolean, val checks: Map<String, HealthState>)
