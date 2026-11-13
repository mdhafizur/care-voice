package com.carevoice.documentation.application

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

        /** Exact, case-sensitive match on the enum name; an unknown role gives null. */
        fun parse(raw: String): Role? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * The authenticated caller, taken from the verified JWT. Every query is scoped to [tenantId] and [wardId]
 * (tenant/ward isolation), which is why use cases take a [Caller] instead of loose strings.
 */
data class Caller(val username: String, val tenantId: String, val wardId: String, val roles: Set<Role>) {
    /** True when the caller has at least one of the [allowed] roles. */
    fun hasAnyRole(allowed: Set<Role>): Boolean = roles.any { it in allowed }
}
