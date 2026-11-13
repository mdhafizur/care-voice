package com.carevoice.alert.infrastructure.migrations

import com.carevoice.alert.logging.LogType
import com.carevoice.alert.logging.event
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.bson.Document
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** The database and the code disagree in a way that must stop the service (newer DB, edited or pending migrations). */
class MigrationException(message: String) : RuntimeException(message)

/**
 * Versioned migration runner for MongoDB (docs/CONTRACTS.md, section 14).
 *
 * - `schema_migrations` holds one document per applied version (unique index on `version`), with description,
 *   appliedAt and checksum.
 * - Concurrency across pods: a lock document `{_id: "migration-lock"}` with an owner and an expiry. Acquiring it is one
 *   atomic upsert that only matches a free or EXPIRED lock, so two pods starting together never apply the same
 *   migration twice, and a pod that crashed while holding the lock does not block deployments forever.
 * - Refuses to run when the database already has a version this code does not know (a rollback to older code must
 *   not run against a schema it does not understand), or when an applied migration's checksum changed.
 *
 * [migrate] applies pending migrations; [validate] (used when `MONGO_MIGRATE_ON_STARTUP=false`) only checks and fails
 * on anything pending, so in Kubernetes the pre-upgrade Job is the only writer of the schema.
 */
class MongoMigrationRunner(
    private val database: MongoDatabase,
    private val migrations: List<MongoMigration> = Migrations.ALL,
    private val clock: Clock = Clock.systemUTC(),
    private val owner: String = "alert-service-${UUID.randomUUID()}",
    private val lockTtl: Duration = 5.minutes,
    private val lockWait: Duration = 2.minutes,
    private val pollInterval: Duration = 500.milliseconds,
) {
    private val log = LoggerFactory.getLogger(MongoMigrationRunner::class.java)
    private val history = database.getCollection(HISTORY_COLLECTION, Document::class.java)
    private val locks = database.getCollection(LOCK_COLLECTION, Document::class.java)

    init {
        val versions = migrations.map { it.version }
        require(versions == versions.sorted() && versions.toSet().size == versions.size) {
            "Migrations must have strictly increasing, unique versions: $versions"
        }
    }

    /** Applies every pending migration in order. Returns the versions applied by THIS call. */
    suspend fun migrate(): List<Int> {
        history.createIndex(
            Indexes.ascending(VERSION),
            IndexOptions().name("ux_schema_migrations_version").unique(true),
        )
        acquireLock()
        try {
            val applied = appliedMigrations()
            verifyCompatible(applied)
            val pending = migrations.filter { it.version !in applied }
            for (migration in pending) {
                log.event(
                    Level.INFO,
                    LogType.DB_OPERATION,
                    "Applying Mongo migration",
                    "version" to migration.version,
                    "description" to migration.description,
                )
                migration.apply(database)
                record(migration)
                renewLock()
            }
            if (pending.isEmpty()) {
                log.event(
                    Level.INFO,
                    LogType.DB_OPERATION,
                    "Mongo schema is up to date",
                    "version" to (migrations.lastOrNull()?.version ?: 0),
                )
            }
            return pending.map { it.version }
        } finally {
            releaseLock()
        }
    }

    /** Checks without changing anything; throws [MigrationException] unless the schema matches the code exactly. */
    suspend fun validate() {
        val applied = appliedMigrations()
        verifyCompatible(applied)
        val pending = migrations.filter { it.version !in applied }.map { it.version }
        if (pending.isNotEmpty()) {
            throw MigrationException(
                "Mongo schema has pending migrations $pending and MONGO_MIGRATE_ON_STARTUP=false; run 'migrate' first.",
            )
        }
    }

    /** All recorded migrations, keyed by version. */
    private suspend fun appliedMigrations(): Map<Int, Document> =
        history.find().sort(Sorts.ascending(VERSION)).toList().associateBy { it.getInteger(VERSION) }

    /**
     * Fails when the database has a version this code does not know, or when the checksum of an applied
     * migration no longer matches the code (someone edited it).
     */
    private fun verifyCompatible(applied: Map<Int, Document>) {
        val known = migrations.associateBy { it.version }
        val unknown = applied.keys.filter { it !in known }
        val latestKnown = migrations.lastOrNull()?.version ?: 0
        if (unknown.isNotEmpty()) {
            throw MigrationException(
                "The database has migrations $unknown that this code does not know " +
                    "(code is at version $latestKnown). " +
                    "Refusing to start: the database is newer than the code.",
            )
        }
        for ((version, doc) in applied) {
            val expected = known.getValue(version).checksum
            val actual = doc.getString(CHECKSUM)
            if (actual != expected) {
                throw MigrationException(
                    "Checksum mismatch for applied migration V$version (database $actual, code $expected). " +
                        "Applied migrations must never be edited.",
                )
            }
        }
    }

    /** Writes the history record. A duplicate version is ignored, because then the record already exists. */
    private suspend fun record(migration: MongoMigration) {
        try {
            history.insertOne(
                Document(VERSION, migration.version)
                    .append(DESCRIPTION, migration.description)
                    .append(APPLIED_AT, Date.from(clock.instant()))
                    .append(CHECKSUM, migration.checksum),
            )
        } catch (e: MongoWriteException) {
            if (e.code != DUPLICATE_KEY) throw e
        }
    }

    /** Tries to take the lock every [pollInterval] until it works; fails after [lockWait]. */
    private suspend fun acquireLock() {
        val deadline = clock.instant().plusMillis(lockWait.inWholeMilliseconds)
        while (true) {
            if (tryAcquireLock()) return
            if (clock.instant().isAfter(deadline)) {
                throw MigrationException("Could not acquire the Mongo migration lock within $lockWait.")
            }
            log.event(Level.INFO, LogType.DB_OPERATION, "Waiting for the Mongo migration lock held by another instance")
            delay(pollInterval)
        }
    }

    /**
     * Atomic: matches the lock only when it is free or expired; when it is held, the upsert tries to insert a second
     * document with the same `_id` and fails with a duplicate key — which means "someone else holds it".
     */
    private suspend fun tryAcquireLock(): Boolean {
        val now = clock.instant()
        val filter =
            Filters.and(
                Filters.eq("_id", LOCK_ID),
                Filters.or(Filters.eq(LOCKED, false), Filters.lt(EXPIRES_AT, Date.from(now))),
            )
        return try {
            locks.findOneAndUpdate(
                filter,
                Updates.combine(
                    Updates.set(LOCKED, true),
                    Updates.set(OWNER, owner),
                    Updates.set(EXPIRES_AT, Date.from(expiryFrom(now))),
                ),
                FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
            )?.getString(OWNER) == owner
        } catch (e: com.mongodb.MongoCommandException) {
            if (e.errorCode == DUPLICATE_KEY) false else throw e
        } catch (e: MongoWriteException) {
            if (e.code == DUPLICATE_KEY) false else throw e
        }
    }

    /** Moves the lock expiry forward after each migration, so a long run does not lose the lock to another pod. */
    private suspend fun renewLock() {
        locks.updateOne(
            Filters.and(Filters.eq("_id", LOCK_ID), Filters.eq(OWNER, owner)),
            Updates.set(EXPIRES_AT, Date.from(expiryFrom(clock.instant()))),
        )
    }

    /**
     * Runs from `finally`, so it also runs while the migration is being cancelled (SIGTERM during a rollout). In a
     * cancelled coroutine a plain suspending call would fail at once and leave the lock until its TTL; NonCancellable
     * lets this one write finish. The write itself is bounded by the client's server-selection and socket timeouts.
     */
    private suspend fun releaseLock(): Unit = withContext(NonCancellable) {
        try {
            locks.updateOne(
                Filters.and(Filters.eq("_id", LOCK_ID), Filters.eq(OWNER, owner)),
                Updates.combine(Updates.set(LOCKED, false), Updates.set(EXPIRES_AT, Date.from(Instant.EPOCH))),
            )
        } catch (e: Exception) {
            // No CancellationException can arrive here (NonCancellable); a failed release only delays the next run.
            lockReleaseFailed(e)
        }
        Unit
    }

    /** Logs a failed release; the lock then frees itself when it expires. */
    private fun lockReleaseFailed(cause: Exception) {
        log.event(
            Level.WARN,
            LogType.DB_OPERATION,
            "Could not release the Mongo migration lock; it expires by itself",
            "lockTtl" to lockTtl.toString(),
            cause = cause,
        )
    }

    /** Lock expiry time: [now] plus [lockTtl]. */
    private fun expiryFrom(now: Instant): Instant = now.plusMillis(lockTtl.inWholeMilliseconds)

    companion object {
        const val HISTORY_COLLECTION = "schema_migrations"
        const val LOCK_COLLECTION = "schema_migrations_lock"
        const val LOCK_ID = "migration-lock"
        private const val VERSION = "version"
        private const val DESCRIPTION = "description"
        private const val APPLIED_AT = "appliedAt"
        private const val CHECKSUM = "checksum"
        private const val LOCKED = "locked"
        private const val OWNER = "owner"
        private const val EXPIRES_AT = "expiresAt"
        private const val DUPLICATE_KEY = 11000
    }
}
