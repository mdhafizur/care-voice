package com.carevoice.documentation.outbox

import com.carevoice.documentation.resilience.Backoff
import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

/**
 * Saves the result of one publish on its outbox row: published, retry later (with backoff), dead, or parked while
 * the circuit breaker is open. Part of the [OutboxRelay]; it uses the relay's [Random] for the jitter.
 */
internal class PublishResultRecorder(
    private val repository: OutboxRepository,
    private val settings: OutboxSettings,
    private val clock: Clock,
    private val random: Random,
) {
    /** Applies [result] to the row (blocking JDBC, called on Dispatchers.IO) and returns the metric outcome. */
    fun record(claimed: ClaimedEvent, result: PublishResult): PublishOutcome {
        val now = clock.instant()
        val (outcome, applied) =
            when (result) {
                PublishResult.Published -> PublishOutcome.PUBLISHED to repository.markPublished(claimed, now)

                is PublishResult.NonRetryable ->
                    PublishOutcome.DEAD to
                        repository.markDead(claimed, claimed.event.attempts + 1, result.reason)

                is PublishResult.Retryable -> {
                    // Give up only when both limits are passed: enough attempts and enough time since the event
                    // was created.
                    val attempts = claimed.event.attempts + 1
                    val age = (now.toEpochMilli() - claimed.createdAt.toEpochMilli()).milliseconds
                    if (attempts >= settings.maxAttempts && age >= settings.retryFor) {
                        PublishOutcome.DEAD to
                            repository.markDead(claimed, attempts, "gave up after $attempts attempts: ${result.reason}")
                    } else {
                        PublishOutcome.RETRY to
                            repository.scheduleRetry(
                                claimed,
                                attempts,
                                now + retryDelay(attempts, result).toJavaDuration(),
                                result.reason,
                            )
                    }
                }

                // The breaker did not call alert-service, so the event itself did not fail. Counting an attempt would
                // let an outage of alert-service burn through maxAttempts within minutes and mark perfectly valid
                // events DEAD (each claim would be rejected instantly). Instead the row is parked for roughly one
                // lease, spread out so that rows trickle back in when the breaker half-opens instead of stampeding.
                PublishResult.CircuitOpen ->
                    PublishOutcome.CIRCUIT_OPEN to
                        repository.reschedule(claimed, now + circuitOpenDelay().toJavaDuration())
            }
        if (!applied) {
            log.event(
                Level.WARN,
                LogType.DB_OPERATION,
                "Outbox row was re-claimed by another instance, outcome dropped",
                "result" to outcome.tag,
            )
        }
        return outcome
    }

    /** Our full-jitter backoff, unless the consumer explicitly asked for a longer pause with `Retry-After`. */
    private fun retryDelay(attempts: Int, result: PublishResult.Retryable): Duration {
        // attempts is 1 after the first failure, whose window is base * 2^0.
        val backoff = Backoff.fullJitter(attempts - 1, settings.backoffBase, settings.backoffCap, random)
        val delay = maxOf(backoff, result.retryAfter ?: Duration.ZERO)
        log.event(
            Level.DEBUG,
            LogType.BUSINESS,
            "Outbox retry scheduled",
            "attempt" to attempts,
            "maxAttempts" to settings.maxAttempts,
            "delayMs" to delay.inWholeMilliseconds,
            "retryAfterHonoured" to (result.retryAfter != null && result.retryAfter >= backoff),
        )
        return delay
    }

    /**
     * "Equal jitter" here (half fixed, half random) rather than full jitter: a delay near zero would re-claim the row
     * immediately and spin on the open breaker; the fixed half guarantees a real pause.
     */
    private fun circuitOpenDelay(): Duration {
        val half = settings.lease / 2
        return (half + Backoff.fullJitter(0, half, half, random)).also {
            log.event(
                Level.DEBUG,
                LogType.BUSINESS,
                "Outbox row parked: circuit open",
                "delayMs" to it.inWholeMilliseconds,
            )
        }
    }

    private companion object {
        /** The relay's logger, so these log lines keep the logger name they had inside the relay. */
        val log = LoggerFactory.getLogger(OutboxRelay::class.java)
    }
}
