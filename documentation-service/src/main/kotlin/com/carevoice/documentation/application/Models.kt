package com.carevoice.documentation.application

import com.carevoice.domain.model.Observation
import com.carevoice.domain.model.ObservationStatus
import com.carevoice.domain.model.VitalType
import java.time.Instant
import java.util.UUID

/*
 * The records the use cases read and write: residents, user accounts, observations, transcripts, audit entries and
 * the observation search filter. Who is calling lives in Caller.kt, paging in Paging.kt.
 */

/** A resident with the display name already decrypted. Lives only in memory (and in the local L1 cache). */
data class Resident(val id: String, val tenantId: String, val wardId: String, val displayName: String)

/** A login account; [passwordHash] is a bcrypt hash, never a password. */
data class UserAccount(
    val username: String,
    val tenantId: String,
    val wardId: String,
    val role: Role,
    val passwordHash: String,
) {
    /** Leaves out [passwordHash], so the account can be logged safely. */
    override fun toString(): String = "UserAccount(username=$username, tenantId=$tenantId, wardId=$wardId, role=$role)"
}

/**
 * A stored observation: the domain [Observation] plus the persistence facts the domain does not know about
 * (scope, transcript, confirmation and the optimistic-locking [version]).
 */
data class ObservationRecord(
    val observation: Observation,
    val tenantId: String,
    val wardId: String,
    val transcriptId: UUID,
    val confirmedBy: String?,
    val confirmedAt: Instant?,
    val version: Int,
) {
    val id: UUID get() = observation.id.value
    val recordedAt: Instant get() = observation.recordedAt
}

/** A stored transcript without its (encrypted) text; [result] is the response returned on first processing. */
data class TranscriptRecord(
    val id: UUID,
    val tenantId: String,
    val wardId: String,
    val residentId: String,
    val createdBy: String,
    val idempotencyKey: UUID,
    val requestHash: String,
    val result: TranscriptResult,
    val createdAt: Instant,
)

/** A row for the compliance audit log. Ids and action names only, never personal data. */
data class AuditEntry(
    val tenantId: String,
    val actor: String,
    val action: String,
    val entityType: String,
    val entityId: String,
    val occurredAt: Instant,
    val details: Map<String, String> = emptyMap(),
)

/** Filter of `QUERY /v1/observations` after validation; always combined with the caller's tenant and ward. */
data class ObservationFilter(
    val residentIds: Set<String> = emptySet(),
    val vitalTypes: Set<VitalType> = emptySet(),
    val status: ObservationStatus? = null,
    val recordedFrom: Instant? = null,
    val recordedTo: Instant? = null,
)
