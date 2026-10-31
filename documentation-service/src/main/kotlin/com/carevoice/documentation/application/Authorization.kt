package com.carevoice.documentation.application

/**
 * Role check shared by every use case (docs/CONTRACTS.md, section 5). The HTTP layer checks roles too (so SSE can
 * answer 403 before the stream starts); checking again here keeps the rule true for every caller of the use case,
 * not just for the routes that remembered to.
 */
internal fun Caller.requireAnyRole(allowed: Set<Role> = Role.CARE_STAFF) {
    if (!hasAnyRole(allowed)) throw ApplicationError.Forbidden()
}

/** Tenant/ward isolation: a resource of another ward is "not found", never "forbidden" (do not reveal it exists). */
internal fun Caller.canSee(tenantId: String, wardId: String): Boolean =
    this.tenantId == tenantId && this.wardId == wardId
