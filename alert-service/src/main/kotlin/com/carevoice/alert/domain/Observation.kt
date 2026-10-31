package com.carevoice.alert.domain

import java.time.Instant

/**
 * One vital sign as the alert rules see it. A sealed hierarchy, so every `when` over it is checked for
 * exhaustiveness by the compiler: adding a vital type forces every rule and mapper to decide what to do with it.
 */
sealed interface Vital {
    data class BloodPressure(val systolic: Int, val diastolic: Int) : Vital

    data class Pulse(val bpm: Int) : Vital

    data class BodyTemperature(val celsius: Double) : Vital
}

/** How the value was extracted from the transcript. */
enum class ObservationSource { RULES, AI }

/**
 * `NEEDS_REVIEW` values (AI or low confidence) still raise alerts — missing a hypertensive crisis because nobody has
 * confirmed the value yet is worse than a false alarm — but the alert message says the value is unconfirmed.
 */
enum class ObservationStatus { CONFIRMED, NEEDS_REVIEW }

/** The observation an event reports; the input of every [AlertRule]. */
data class Observation(
    val observationId: String,
    val tenantId: String,
    val wardId: String,
    val residentId: String,
    val vital: Vital,
    val source: ObservationSource,
    val status: ObservationStatus,
    val observedAt: Instant,
)
