package com.carevoice.documentation.api

import com.carevoice.documentation.application.PageCursor
import com.carevoice.documentation.application.Resident
import com.carevoice.documentation.application.ResidentRepository
import com.carevoice.documentation.outbox.OutboxTestFixtures
import com.carevoice.documentation.support.AuthenticationContract
import com.carevoice.documentation.support.AuthorizationContract
import com.carevoice.documentation.support.Payloads
import com.carevoice.documentation.support.TestScenario
import com.carevoice.documentation.support.assertMatchesSpec
import com.carevoice.documentation.support.assertProblem
import com.carevoice.documentation.support.follow
import com.carevoice.documentation.support.listObservations
import com.carevoice.documentation.support.parseLinks
import com.carevoice.documentation.support.submitTranscript
import com.carevoice.documentation.support.withApp
import com.carevoice.testsupport.assertJsonEquals
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

@DisplayName("GET /v1/residents/{residentId}/observations")
class ListResidentObservationsTest :
    AuthenticationContract,
    AuthorizationContract {
    override val specPath = PATH
    override val specMethod = "get"

    override suspend fun send(client: HttpClient, scenario: TestScenario, token: String?): HttpResponse =
        client.listObservations(scenario, token = token)

    override suspend fun sendForOtherWard(client: HttpClient, scenario: TestScenario): HttpResponse =
        client.listObservations(scenario, residentId = scenario.residentB1)

    /** Submits [count] single-pulse transcripts for resident A1, one minute apart; returns observation ids newest first. */
    private suspend fun HttpClient.recordPulses(s: TestScenario, count: Int): List<String> = List(count) { i ->
        s.clock.advance(1.minutes)
        assertThat(submitTranscript(s, body = Payloads.pulse(60 + i)).status).isEqualTo(HttpStatusCode.Created)
        s.observationIds.expected(s.observationIds.handedOut).toString()
    }.reversed()

    private fun itemIds(body: String): List<String> =
        Json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray.map {
            it.jsonObject["id"]!!.jsonPrimitive.content
        }

    @Nested
    @DisplayName("General Request Structure")
    inner class GeneralRequestStructure {
        @Test
        fun `should not offer a prev link on the first page`() = withApp { s ->
            client.recordPulses(s, 5)

            val page1 = client.listObservations(s, params = mapOf("limit" to "2"))

            page1.assertMatchesSpec(PATH, "get")
            val links = parseLinks(page1.headers[HttpHeaders.Link])
            assertThat(links).containsKeys("first", "next").doesNotContainKey("prev")
        }

        @Test
        fun `should return page 1 again when following next and then prev`() = withApp { s ->
            client.recordPulses(s, 5)
            val token = s.nurseToken()
            val page1 = client.listObservations(s, params = mapOf("limit" to "2"))
            val page1Body = page1.assertMatchesSpec(PATH, "get")

            val page2 = client.follow(parseLinks(page1.headers[HttpHeaders.Link]).getValue("next"), token)
            page2.assertMatchesSpec(PATH, "get")
            val back = client.follow(parseLinks(page2.headers[HttpHeaders.Link]).getValue("prev"), token)

            assertThat(itemIds(back.assertMatchesSpec(PATH, "get"))).isEqualTo(itemIds(page1Body))
        }

        @Test
        fun `should visit every item exactly once when walking next links, matching X-Total-Count`() = withApp { s ->
            val expected = client.recordPulses(s, 7)
            val token = s.nurseToken()

            val visited = mutableListOf<String>()
            var response = client.listObservations(s, params = mapOf("limit" to "3"))
            val total = response.headers["X-Total-Count"]!!.toInt()
            while (true) {
                visited += itemIds(response.assertMatchesSpec(PATH, "get"))
                val next = parseLinks(response.headers[HttpHeaders.Link])["next"] ?: break
                response = client.follow(next, token)
            }

            assertThat(total).isEqualTo(7)
            assertThat(visited).containsExactlyElementsOf(expected)
        }

        @Test
        fun `should neither shift nor duplicate items when a new observation is inserted between fetches`() =
            withApp { s ->
                val expected = client.recordPulses(s, 4)
                val token = s.nurseToken()
                val page1 = client.listObservations(s, params = mapOf("limit" to "2"))
                val page1Ids = itemIds(page1.assertMatchesSpec(PATH, "get"))

                client.recordPulses(s, 1)
                val page2 = client.follow(parseLinks(page1.headers[HttpHeaders.Link]).getValue("next"), token)

                assertThat(page1Ids + itemIds(page2.assertMatchesSpec(PATH, "get"))).containsExactlyElementsOf(expected)
                assertThat(page2.headers["X-Total-Count"]).isEqualTo("5")
            }

        @Test
        fun `should return the first page for a well-formed cursor that matches no row`() = withApp { s ->
            val expected = client.recordPulses(s, 2)
            val unknown =
                PageCursor(PageCursor.Direction.AFTER, OutboxTestFixtures.FIXED_NOW, UUID.randomUUID()).encode()

            val response = client.listObservations(s, params = mapOf("cursor" to unknown))

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(itemIds(response.assertMatchesSpec(PATH, "get"))).containsExactlyElementsOf(expected)
        }

        @ParameterizedTest(name = "should return 200 when limit is {0}")
        @FieldSource("com.carevoice.documentation.support.DocumentationTestValues#validLimits")
        fun `should accept limits at both bounds`(limit: String) = withApp { s ->
            val response = client.listObservations(s, params = mapOf("limit" to limit))
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            response.assertMatchesSpec(PATH, "get")
        }
    }

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @ParameterizedTest(name = "should return 400 when limit is {0}")
        @FieldSource("com.carevoice.documentation.support.DocumentationTestValues#invalidLimits")
        fun `should return 400 Bad Request when the limit is invalid`(limit: String) = withApp { s ->
            client
                .listObservations(s, params = mapOf("limit" to limit))
                .assertProblem(PATH, "get", HttpStatusCode.BadRequest, "validation-error")
        }

        @ParameterizedTest(name = "should return 400 when cursor is {0}")
        @FieldSource("com.carevoice.documentation.support.DocumentationTestValues#malformedCursors")
        fun `should return 400 Bad Request when the cursor is malformed`(cursor: String) = withApp { s ->
            client
                .listObservations(s, params = mapOf("cursor" to cursor))
                .assertProblem(PATH, "get", HttpStatusCode.BadRequest, "validation-error")
        }

        @Test
        fun `should return 404 Not Found when the resident does not exist`() = withApp { s ->
            client
                .listObservations(s, residentId = "${s.tenantId}-unknown")
                .assertProblem(PATH, "get", HttpStatusCode.NotFound, "not-found")
        }

        @Test
        fun `should return 500 without internal details when a port fails unexpectedly`() =
            withApp(TestScenario.create(residents = { ThrowingResidents })) { s ->
                val response = client.listObservations(s)
                val problem = response.assertProblem(PATH, "get", HttpStatusCode.InternalServerError, "internal-error")
                assertThat(problem["detail"]!!.jsonPrimitive.content).isEqualTo("An unexpected error occurred.")
                assertThat(response.bodyAsText()).doesNotContain(SECRET_FAILURE)
            }
    }

    @Nested
    @DisplayName("Response Validation")
    inner class ResponseValidation {
        @Test
        fun `should return 200 with the full list body, Link, X-Total-Count and a weak ETag`() = withApp { s ->
            client.recordPulses(s, 1)
            val observationId = s.observationIds.expected(1)
            val recordedAt = OutboxTestFixtures.FIXED_NOW.plusSeconds(60)

            val response = client.listObservations(s)

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers["X-Total-Count"]).isEqualTo("1")
            assertThat(response.headers[HttpHeaders.ETag]).startsWith("W/\"")
            assertThat(response.headers[HttpHeaders.CacheControl]).isEqualTo("private, no-cache")
            assertThat(parseLinks(response.headers[HttpHeaders.Link]))
                .containsExactlyEntriesOf(mapOf("first" to "/v1/residents/${s.residentA1}/observations"))
            assertJsonEquals(
                """
                    {
                      "items": [
                        {"id":"$observationId","residentId":"${s.residentA1}","vital":{"type":"PULSE","bpm":60},
                         "source":"RULES","confidence":0.95,"status":"CONFIRMED","recordedAt":"$recordedAt",
                         "confirmedBy":null,"confirmedAt":null,"version":0}
                      ],
                      "nextCursor": null
                    }
                    """,
                response.assertMatchesSpec(PATH, "get"),
            )
        }

        @Test
        fun `should return 304 Not Modified when If-None-Match carries the current ETag`() = withApp { s ->
            client.recordPulses(s, 1)
            val etag = client.listObservations(s).headers[HttpHeaders.ETag]!!

            val response = client.listObservations(s, ifNoneMatch = etag)

            assertThat(response.status).isEqualTo(HttpStatusCode.NotModified)
            assertThat(response.headers[HttpHeaders.ETag]).isEqualTo(etag)
            response.assertMatchesSpec(PATH, "get")
        }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should change the ETag once a new observation is recorded`() = withApp { s ->
            client.recordPulses(s, 1)
            val etag = client.listObservations(s).headers[HttpHeaders.ETag]!!

            client.recordPulses(s, 1)
            val response = client.listObservations(s, ifNoneMatch = etag)

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.ETag]).isNotEqualTo(etag)
            assertThat(itemIds(response.assertMatchesSpec(PATH, "get"))).hasSize(2)
        }

        @Test
        fun `should list only the requested resident's observations`() = withApp { s ->
            val own = client.recordPulses(s, 2)
            client.submitTranscript(s, body = Payloads.pulse(90), residentId = s.residentA2)

            val response = client.listObservations(s)

            assertThat(itemIds(response.assertMatchesSpec(PATH, "get"))).containsExactlyElementsOf(own)
            assertThat(response.headers["X-Total-Count"]).isEqualTo("2")
        }
    }

    private object ThrowingResidents : ResidentRepository {
        override suspend fun findById(tenantId: String, residentId: String): Resident? =
            throw IllegalStateException(SECRET_FAILURE)
    }

    private companion object {
        const val PATH = "/v1/residents/{residentId}/observations"
        const val SECRET_FAILURE = "connection to db-internal-7 refused"
    }
}
