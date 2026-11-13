package com.carevoice.documentation.application

import com.carevoice.contracts.ObservationRecordedEvent
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/*
 * Ports (hexagonal architecture): what the use cases need, expressed in application terms only. The adapters in
 * `infrastructure/` decide HOW (PostgreSQL, Redis, AES, bcrypt, JWT); tests substitute in-memory fakes. No port
 * mentions JDBC, Lettuce or Ktor, which is what keeps the use cases independent of those technologies.
 *
 * Repository ports are `suspend` and participate in the ambient transaction opened by [TransactionRunner]: called
 * inside `inTransaction { }` they use that transaction, called outside they run in their own short one.
 */

/** Repository pattern: residents with decrypted display names. */
interface ResidentRepository {
    /** The resident within [tenantId], or null. Ward scoping is the caller's rule, applied by the use case. */
    suspend fun findById(tenantId: String, residentId: String): Resident?
}

/** Result of inserting a transcript under an idempotency key. */
sealed interface TranscriptInsert {
    data object Inserted : TranscriptInsert

    /** The unique constraint `(created_by, idempotency_key)` rejected the row: another request won the race. */
    data object DuplicateIdempotencyKey : TranscriptInsert
}

/** Repository pattern: transcripts. The raw text is encrypted by the adapter and never read back by the API. */
interface TranscriptRepository {
    /**
     * Inserts the transcript with its encrypted [text]. Returns [TranscriptInsert.DuplicateIdempotencyKey] on a
     * unique violation; the surrounding transaction is then unusable and must be rolled back by the caller.
     */
    suspend fun insert(record: TranscriptRecord, text: String): TranscriptInsert

    /** The user's earlier transcript with this idempotency key, or null. Used to replay a repeated request. */
    suspend fun findByIdempotencyKey(createdBy: String, idempotencyKey: UUID): TranscriptRecord?

    /** Scoped to tenant AND ward (via the resident): a transcript of another ward is "not found". */
    suspend fun findById(tenantId: String, wardId: String, transcriptId: UUID): TranscriptRecord?

    /** Erases (sets NULL) the raw text of up to [batchSize] transcripts created before [cutoff]; returns the count. */
    suspend fun eraseTextCreatedBefore(cutoff: Instant, batchSize: Int): Int
}

/** Outcome of an optimistic-locking update. */
sealed interface ConfirmOutcome {
    data class Confirmed(val record: ObservationRecord) : ConfirmOutcome

    /** The row exists but its version is not the expected one (someone else changed it first). */
    data object VersionMismatch : ConfirmOutcome

    data object NotFound : ConfirmOutcome
}

/** Repository pattern: observations, keyset-paginated (docs/CONTRACTS.md, section 14). */
interface ObservationRepository {
    /** Stores new observations together, in one call. */
    suspend fun insertAll(records: List<ObservationRecord>)

    /** One observation, but only when it belongs to [tenantId] and [wardId]; otherwise null. */
    suspend fun findById(tenantId: String, wardId: String, observationId: UUID): ObservationRecord?

    /** One page of a resident's observations in the ward, newest first. */
    suspend fun listByResident(
        tenantId: String,
        wardId: String,
        residentId: String,
        page: PageRequest,
    ): Page<ObservationRecord>

    /** One page of the ward's observations that match [filter], newest first. */
    suspend fun query(
        tenantId: String,
        wardId: String,
        filter: ObservationFilter,
        page: PageRequest,
    ): Page<ObservationRecord>

    /**
     * Confirms a NEEDS_REVIEW observation if its version still equals [expectedVersion]
     * (`UPDATE ... WHERE id = ? AND version = ?`), incrementing the version.
     */
    suspend fun confirm(
        tenantId: String,
        wardId: String,
        observationId: UUID,
        expectedVersion: Int,
        confirmedBy: String,
        confirmedAt: Instant,
    ): ConfirmOutcome

    /**
     * Observations of the ward recorded after the observation [afterObservationId] in `(recorded_at, id)` order,
     * oldest first. Used to replay SSE events missed while a client reconnected (`Last-Event-ID`).
     * Returns an empty list when the id is unknown in this ward.
     */
    suspend fun findAfter(
        tenantId: String,
        wardId: String,
        afterObservationId: UUID,
        limit: Int,
    ): List<ObservationRecord>
}

/** Repository pattern: login accounts. */
interface UserRepository {
    /** The account with exactly this username, or null when there is none. */
    suspend fun findByUsername(username: String): UserAccount?
}

/** Static demo data; inserted with ON CONFLICT DO NOTHING so concurrent pods and restarts are harmless. */
data class DemoDataset(
    val tenantId: String,
    val tenantName: String,
    val wards: List<DemoWard>,
    val residents: List<Resident>,
    val users: List<UserAccount>,
)

data class DemoWard(val id: String, val name: String)

/** Repository pattern: idempotent insertion of the demo tenant (never a migration: seed data is not schema). */
interface DemoDataRepository {
    /** Returns the number of rows actually inserted (0 when everything already existed). */
    suspend fun insertIfAbsent(dataset: DemoDataset): Int
}

/** Compliance audit trail, written inside the business transaction. */
fun interface AuditLog {
    /** Writes one audit row. Called inside the business transaction, so it commits or rolls back with it. */
    suspend fun record(entry: AuditEntry)
}

/**
 * Transactional outbox port: appends an event inside the CURRENT transaction (Transactional Outbox pattern), so the
 * event exists if and only if the observation was committed.
 */
fun interface EventOutbox {
    /** Adds [event] to the outbox in the current transaction; [correlation] is stored with the row. */
    suspend fun append(event: ObservationRecordedEvent, correlation: Correlation)
}

/** Request correlation captured on the request coroutine and stored with the outbox row. */
data class Correlation(val requestId: String?, val traceparent: String?) {
    companion object {
        val NONE = Correlation(null, null)
    }
}

/** Adapter port over the log MDC and OpenTelemetry's current span. */
fun interface CorrelationProvider {
    /** The correlation ids of the current request; a field is null when it is not known. */
    fun current(): Correlation
}

/**
 * Unit of Work: runs a block in ONE database transaction (READ COMMITTED). Everything the block does through the
 * repositories commits together or not at all.
 */
interface TransactionRunner {
    /** Runs [block] in one transaction: commit when it returns, roll back when it throws. */
    suspend fun <T> inTransaction(block: suspend () -> T): T

    /**
     * Runs [block] in a transaction holding the transaction-scoped advisory lock named [lockName]
     * (`pg_try_advisory_xact_lock`). Returns null without running [block] when another instance holds the lock.
     * Transaction-scoped on purpose: it is released at commit and works behind PgBouncer in transaction mode.
     */
    suspend fun <T : Any> tryWithAdvisoryLock(lockName: String, block: suspend () -> T): T?
}

/** Encrypts personal data at rest. Ciphertexts carry their key id, so old keys stay readable after rotation. */
interface FieldEncryptor {
    /** Returns a ciphertext that names the key it was made with. */
    fun encrypt(plaintext: String): String

    /** Reverses [encrypt] with the key named in [ciphertext]; throws when it cannot be decrypted. */
    fun decrypt(ciphertext: String): String
}

interface PasswordHasher {
    /** Returns a one-way hash of [password], to store instead of the password itself. */
    fun hash(password: String): String

    /** True when [password] matches [hash], a value that the hash function returned earlier. */
    fun verify(password: String, hash: String): Boolean

    /** A valid hash of a random password, verified against when the user does not exist (constant-time login). */
    val dummyHash: String
}

data class IssuedToken(val accessToken: String, val expiresIn: Duration)

/** Creates access tokens for users who have logged in. */
fun interface TokenIssuer {
    /** Creates an access token for [user], with how long it stays valid. */
    fun issue(user: UserAccount): IssuedToken
}

sealed interface RateLimitDecision {
    data class Allowed(val remaining: Long, val resetIn: Duration) : RateLimitDecision

    data class Rejected(val retryAfter: Duration) : RateLimitDecision
}

/** Shared (cross-replica) rate limiting. Adapters fail OPEN when their backing store is unavailable. */
fun interface DistributedRateLimiter {
    /** Counts one request for [key] in [scope] and decides whether it is still within [limit] per [window]. */
    suspend fun tryAcquire(scope: String, key: String, limit: Int, window: Duration): RateLimitDecision
}

/** Messages on `carevoice:cache-invalidation`. Ids only: the bus never carries personal data. */
sealed interface CacheInvalidation {
    data class ObservationQuery(val tenantId: String, val wardId: String) : CacheInvalidation

    data class ResidentEntry(val residentId: String) : CacheInvalidation
}

/** Observer pattern across replicas: cache invalidation messages reach every instance, including the sender. */
interface CacheInvalidationBus {
    /** Sends [message] to every instance, this one included. */
    suspend fun publish(message: CacheInvalidation)

    /** Registers [listener] to be called for each message that any instance publishes. */
    fun subscribe(listener: (CacheInvalidation) -> Unit)
}

/**
 * Observer pattern across replicas: newly committed observations reach every instance's SSE clients.
 * Exactly one delivery path per event: the adapter delivers to local listeners either via the shared channel or,
 * when that is unavailable, directly, never both.
 */
interface ObservationBroadcaster {
    /** Sends newly committed [records] to the SSE clients of every instance. */
    suspend fun publish(records: List<ObservationRecord>)

    /** Registers [listener] to be called once for each broadcast observation. */
    fun subscribe(listener: (ObservationRecord) -> Unit)
}

/** Local L1 cache for ward-scoped observation queries (10 s TTL), invalidated per ward. */
interface ObservationQueryCache {
    /** Returns the cached page for [key], or runs [loader] and caches what it returns. */
    suspend fun getOrLoad(
        key: ObservationQueryKey,
        loader: suspend () -> Page<ObservationRecord>,
    ): Page<ObservationRecord>

    /** Drops every cached page of this ward, so the next query reads fresh data. */
    fun invalidateWard(tenantId: String, wardId: String)
}

data class ObservationQueryKey(
    val tenantId: String,
    val wardId: String,
    val filter: ObservationFilter,
    val page: PageRequest,
)

/** Readiness of one dependency, as reported in the `/readyz` body. */
enum class HealthStatus { UP, DEGRADED, DOWN }

/** A readiness probe of one dependency (database, Redis, broker). */
interface HealthProbe {
    val name: String

    /** Critical probes make the service not ready when DOWN; others only report DEGRADED. */
    val critical: Boolean

    /** Checks the dependency now. If it throws, [Readiness] counts it as DOWN (critical) or DEGRADED. */
    suspend fun check(): HealthStatus
}
