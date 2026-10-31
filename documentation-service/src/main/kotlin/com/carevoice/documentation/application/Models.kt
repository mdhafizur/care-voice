package com.carevoice.documentation.application

import com.carevoice.domain.model.Observation
import com.carevoice.domain.model.ObservationStatus
import com.carevoice.domain.model.VitalType
import java.time.Instant
import java.util.UUID

/** JWT claim names shared by the token issuer and the HTTP verifier (docs/CONTRACTS.md, section 5). */
object JwtClaims {
    const val TENANT = "tenant"
    const val WARD = "ward"
    const val ROLES = "roles"
}

/** Roles carried in the JWT `roles` claim (docs/CONTRACTS.md, section 5). */
enum class Role {
    NURSE,
    WARD_LEAD,
    SERVICE,
    ;

    companion object {
        /** Roles that may use the nurse-facing API; `SERVICE` tokens are for service-to-service calls only. */
        val CARE_STAFF: Set<Role> = setOf(NURSE, WARD_LEAD)

        fun parse(raw: String): Role? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * The authenticated caller, taken from the verified JWT. Every query is scoped to [tenantId] and [wardId]
 * (tenant/ward isolation), which is why use cases take a [Caller] instead of loose strings.
 */
data class Caller(val username: String, val tenantId: String, val wardId: String, val roles: Set<Role>) {
    fun hasAnyRole(allowed: Set<Role>): Boolean = roles.any { it in allowed }
}

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

/** One keyset page. [nextCursor]/[prevCursor] are opaque encoded [PageCursor]s, null when there is no such page. */
data class Page<T>(val items: List<T>, val nextCursor: String?, val prevCursor: String?, val totalCount: Long)

/** What `PageRequest.limit` may be (docs/CONTRACTS.md, section 6). */
object PageLimits {
    const val MIN = 1
    const val MAX = 100
    const val DEFAULT = 20
}

data class PageRequest(val limit: Int = PageLimits.DEFAULT, val cursor: PageCursor? = null) {
    init {
        require(limit in PageLimits.MIN..PageLimits.MAX) { "limit must be in ${PageLimits.MIN}..${PageLimits.MAX}" }
    }
}

/** Messages on `carevoice:cache-invalidation`. Ids only: the bus never carries personal data. */
sealed interface CacheInvalidation {
    data class ObservationQuery(val tenantId: String, val wardId: String) : CacheInvalidation

    data class ResidentEntry(val residentId: String) : CacheInvalidation
}

/** Readiness of one dependency, as reported in the `/readyz` body. */
enum class HealthStatus { UP, DEGRADED, DOWN }

data class HealthCheckResult(
    val name: String,
    val status: HealthStatus,
    /** When true, [HealthStatus.DOWN] makes the whole service not ready (503); otherwise it only degrades. */
    val critical: Boolean,
)
