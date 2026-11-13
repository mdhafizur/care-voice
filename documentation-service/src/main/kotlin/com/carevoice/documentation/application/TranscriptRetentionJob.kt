package com.carevoice.documentation.application

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * Background job: erases the raw transcript text after the retention period (GDPR data minimisation). The extracted
 * observations stay; only the free text, which may mention anything a nurse said, disappears.
 *
 * Multi-instance: every pod runs the schedule, but a run only does work while holding the transaction-scoped
 * advisory lock `transcript-retention`; the other pods skip that run (docs/CONTRACTS.md, section 9b). Batches keep
 * each transaction short.
 */
class TranscriptRetentionJob(
    private val transcripts: TranscriptRepository,
    private val transactions: TransactionRunner,
    private val retention: Duration,
    private val clock: Clock,
    private val interval: Duration = 1.hours,
    private val batchSize: Int = 500,
) {
    private val log = LoggerFactory.getLogger(TranscriptRetentionJob::class.java)
    private var job: Job? = null

    /** Launches the loop: one [runOnce] now, then one every [interval]. A failed run is logged and the loop goes on. */
    fun start(scope: CoroutineScope): Job = scope
        .launch(CoroutineName(JOB_NAME) + MDCContext(mapOf("job" to JOB_NAME))) {
            while (isActive) {
                try {
                    runOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.event(Level.ERROR, LogType.DB_OPERATION, "Transcript retention run failed", cause = e)
                }
                delay(interval)
            }
        }.also { job = it }

    /** Cancels the loop and waits until it has stopped. Does nothing when [start] was never called. */
    suspend fun stop() {
        job?.cancelAndJoin()
    }

    /** One run; returns the number of erased texts, or null when another instance holds the lock. */
    suspend fun runOnce(): Int? {
        // Step 1: transcripts created before this time lose their text.
        val cutoff = clock.instant().minus(retention.toJavaDuration())
        // Step 2: take an advisory lock (a named database lock only one holder can have), so only
        // one instance runs the job at a time.
        val erased =
            transactions.tryWithAdvisoryLock(LOCK_NAME) {
                var total = 0
                var batches = 0
                // Step 3: erase in batches until a batch comes back short or the per-run cap is reached.
                // The rest waits for the next run.
                do {
                    val count = transcripts.eraseTextCreatedBefore(cutoff, batchSize)
                    total += count
                    batches++
                    log.event(
                        Level.DEBUG,
                        LogType.DB_OPERATION,
                        "Transcript retention batch erased",
                        "batch" to batches,
                        "erased" to count,
                        "batchSize" to batchSize,
                        "cutoff" to cutoff.toString(),
                    )
                } while (count == batchSize && total < MAX_PER_RUN)
                if (total >= MAX_PER_RUN) {
                    log.event(
                        Level.DEBUG,
                        LogType.DB_OPERATION,
                        "Transcript retention capped; rest next run",
                        "erased" to total,
                    )
                }
                total
            }
        // Step 4: log the result. null means another instance held the lock, so nothing was done.
        if (erased == null) {
            log.event(
                Level.DEBUG,
                LogType.DB_OPERATION,
                "Transcript retention skipped: another instance holds the lock",
            )
        } else {
            log.event(Level.INFO, LogType.DB_OPERATION, "Transcript retention run finished", "erased" to erased)
        }
        return erased
    }

    companion object {
        const val JOB_NAME = "transcript-retention"
        const val LOCK_NAME = "carevoice:transcript-retention"

        /** Caps one run (one transaction) so a large backlog is worked off over several runs. */
        const val MAX_PER_RUN = 50_000
    }
}
