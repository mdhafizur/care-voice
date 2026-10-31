package com.carevoice.alert.support

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Authentication baseline (docs/CONTRACTS.md, section 12). The endpoint class implements it and says how to send its
 * request with a given `Authorization` header value (null = no header).
 */
interface AuthenticationContract {
    val app: AlertTestApp

    /** The spec's path template, e.g. `/v1/alerts/{alertId}`. */
    val specPath: String
    val specMethod: String

    suspend fun ApplicationTestBuilder.sendWith(authorization: String?): HttpResponse

    @Test
    fun `should return 401 Unauthorized when the token is missing`() = assertUnauthorized(null)

    @Test
    fun `should return 401 Unauthorized when the token is malformed`() = assertUnauthorized("Bearer not.a.jwt")

    @Test
    fun `should return 401 Unauthorized when the scheme is not Bearer`() = assertUnauthorized("Basic " + Tokens.token())

    @Test
    fun `should return 401 Unauthorized when the token is expired`() =
        assertUnauthorized(Tokens.bearer(Tokens.token(expiresAt = Instant.now().minusSeconds(3600))))

    @Test
    fun `should return 401 Unauthorized when the issuer is wrong`() =
        assertUnauthorized(Tokens.bearer(Tokens.token(issuer = "someone-else")))

    @Test
    fun `should return 401 Unauthorized when the audience is wrong`() =
        assertUnauthorized(Tokens.bearer(Tokens.token(audience = "other-audience")))

    @Test
    fun `should return 401 Unauthorized when the signature uses another secret`() =
        assertUnauthorized(Tokens.bearer(Tokens.token(secret = "another-secret-that-is-also-32-bytes-long")))

    private fun assertUnauthorized(authorization: String?) = app.run {
        val response = sendWith(authorization)

        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(response.headers[HttpHeaders.WWWAuthenticate]).startsWith("Bearer")
        TestFixtures.openApi.assertResponseMatches(
            specPath,
            specMethod,
            401,
            response.bodyAsText(),
            "application/problem+json",
        )
    }
}

/**
 * Authorization baseline: wrong role → 403; a resource of another ward → 404 (not 403, CONTRACTS.md, section 5).
 */
interface AuthorizationContract : AuthenticationContract {
    /** A valid token whose roles do not allow this endpoint. */
    val wrongRoleAuthorization: String

    /** False for endpoints without a ward-scoped resource in the path (the 404 test is skipped). */
    val hasWardScopedResource: Boolean get() = true

    /** Sends the request for a resource that exists in another ward of the same tenant, as a caller of this ward. */
    suspend fun ApplicationTestBuilder.sendForOtherWardResource(): HttpResponse =
        error("Endpoints with a ward-scoped resource must override sendForOtherWardResource")

    @Test
    fun `should return 403 Forbidden when the caller lacks the required role`() = app.run {
        val response = sendWith(wrongRoleAuthorization)

        assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
        TestFixtures.openApi.assertResponseMatches(
            specPath,
            specMethod,
            403,
            response.bodyAsText(),
            "application/problem+json",
        )
    }

    @Test
    fun `should return 404 Not Found when the resource belongs to another ward`() {
        assumeTrue(hasWardScopedResource, "endpoint has no ward-scoped resource")
        app.run {
            val response = sendForOtherWardResource()

            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
            TestFixtures.openApi.assertResponseMatches(
                specPath,
                specMethod,
                404,
                response.bodyAsText(),
                "application/problem+json",
            )
        }
    }
}
