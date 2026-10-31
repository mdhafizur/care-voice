package com.carevoice.documentation.support

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** The endpoint under test, as the spec names it. */
interface EndpointUnderTest {
    val specPath: String
    val specMethod: String

    /**
     * Sends a request that would succeed for a ward-A nurse of [scenario], with [token] as the bearer token (none when
     * null). Implemented by every endpoint test class.
     */
    suspend fun send(client: HttpClient, scenario: TestScenario, token: String?): HttpResponse
}

/**
 * Authentication baseline (docs/CONTRACTS.md, section 12). Every case is the same 401 with
 * `WWW-Authenticate: Bearer`; the reason is never revealed.
 */
interface AuthenticationContract : EndpointUnderTest {
    private suspend fun HttpResponse.assertUnauthorized() {
        assertProblem(specPath, specMethod, HttpStatusCode.Unauthorized, "unauthorized")
        assertThat(headers[HttpHeaders.WWWAuthenticate]).startsWith("Bearer")
    }

    @Test
    fun `should return 401 Unauthorized when the bearer token is missing`() =
        withApp { s -> send(client, s, null).assertUnauthorized() }

    @Test
    fun `should return 401 Unauthorized when the bearer token is malformed`() =
        withApp { s -> send(client, s, TestTokens.MALFORMED).assertUnauthorized() }

    @Test
    fun `should return 401 Unauthorized when the bearer token is expired`() =
        withApp { s -> send(client, s, s.token(expiresIn = Duration.ofSeconds(-1))).assertUnauthorized() }

    @Test
    fun `should return 401 Unauthorized when the bearer token has the wrong issuer`() =
        withApp { s -> send(client, s, s.token(issuer = "someone-else")).assertUnauthorized() }

    @Test
    fun `should return 401 Unauthorized when the bearer token has the wrong audience`() =
        withApp { s -> send(client, s, s.token(audience = "another-api")).assertUnauthorized() }

    @Test
    fun `should return 401 Unauthorized when the bearer token is signed with another key`() =
        withApp { s -> send(client, s, s.token(secret = TestTokens.OTHER_SECRET)).assertUnauthorized() }
}

/**
 * Authorization baseline: a role outside NURSE/WARD_LEAD is 403; a resource of another ward is 404 (its existence is
 * never revealed). The implementing class provides [sendForOtherWard].
 */
interface AuthorizationContract : EndpointUnderTest {
    /** Sends a request with a valid ward-A nurse token that targets a resource of ward B. */
    suspend fun sendForOtherWard(client: HttpClient, scenario: TestScenario): HttpResponse

    @Test
    fun `should return 403 Forbidden when the caller has the SERVICE role only`() = withApp { s ->
        send(
            client,
            s,
            s.token(roles = listOf("SERVICE")),
        ).assertProblem(specPath, specMethod, HttpStatusCode.Forbidden, "forbidden")
    }

    @Test
    fun `should return 403 Forbidden when the caller has no known role`() = withApp { s ->
        send(
            client,
            s,
            s.token(roles = listOf("JANITOR")),
        ).assertProblem(specPath, specMethod, HttpStatusCode.Forbidden, "forbidden")
    }

    @Test
    fun `should return 404 Not Found when the resource belongs to another ward`() = withApp { s ->
        sendForOtherWard(client, s).assertProblem(specPath, specMethod, HttpStatusCode.NotFound, "not-found")
    }
}
