package com.carevoice.alert.infrastructure.migrations

import com.mongodb.kotlin.client.coroutine.MongoDatabase

/**
 * Command pattern: each schema change is an object with a version, a description and an [apply] method. The runner
 * executes pending commands in order and records each one in `schema_migrations`, which gives an auditable history,
 * "apply once" semantics across pods and a way to detect a database newer than the code.
 *
 * Rules: never edit an applied migration (its [checksum] would change and startup fails); add a new one instead.
 * [apply] must be idempotent, because a pod may crash after applying and before recording.
 */
interface MongoMigration {
    val version: Int
    val description: String

    /**
     * Stable fingerprint of what the migration does. Derived from [definition] so a change to an applied migration is
     * detected at startup.
     */
    val checksum: String get() = sha256(definition)

    /** Canonical, human-readable description of the change, hashed into [checksum]. */
    val definition: String

    /** Makes the change. Must be idempotent (running it twice has the same effect as once). */
    suspend fun apply(database: MongoDatabase)
}

/** SHA-256 hash of [text] as a lowercase hex string; used for migration checksums. */
internal fun sha256(text: String): String = java.security.MessageDigest
    .getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
