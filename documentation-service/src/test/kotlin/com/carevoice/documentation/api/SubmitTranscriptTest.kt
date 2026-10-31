package com.carevoice.documentation.api

import com.carevoice.contracts.ContractJson
import com.carevoice.contracts.ObservationRecordedEvent
import com.carevoice.contracts.VitalPayload
import com.carevoice.documentation.application.Correlation
import com.carevoice.documentation.application.EventOutbox
import com.carevoice.documentation.application.Resident
import com.carevoice.documentation.application.ResidentRepository
import com.carevoice.documentation.outbox.OutboxTestFixtures
import com.carevoice.documentation.support.AuthenticationContract
import com.carevoice.documentation.support.AuthorizationContract
import com.carevoice.documentation.support.Payloads
import com.carevoice.documentation.support.TestDatabase
import com.carevoice.documentation.support.TestScenario
import com.carevoice.documentation.support.assertMatchesSpec
import com.carevoice.documentation.support.assertProblem
import com.carevoice.documentation.support.submitTranscript
import com.carevoice.documentation.support.withApp
import com.carevoice.testsupport.assertJsonEquals
import com.carevoice.testsupport.withField
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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

@DisplayName("POST /v1/residents/{residentId}/transcripts")
class SubmitTranscriptTest :
    AuthenticationContract,
    AuthorizationContract {
    override val specPath = PATH
    override val specMethod = "post"

    override suspend fun send(client: HttpClient, scenario: TestScenario, token: String?): HttpResponse =
        client.submitTranscript(scenario, token = token)

    override suspend fun sendForOtherWard(client: HttpClient, scenario: TestScenario): HttpResponse =
        client.submitTranscript(scenario, residentId = scenario.residentB1)

    /** The 201 body for [Payloads.FULL_TRANSCRIPT] with the scenario's fixed clock and sequential ids. */
    private fun expectedBody(s: TestScenario): String {
        val recordedAt = OutboxTestFixtures.FIXED_NOW.toString()

        fun observation(n: Int, vital: String) =
            """{"id":"${s.observationIds.expected(n)}","residentId":"${s.residentA1}","vital":$vital,"source":"RULES",
               "confidence":0.95,"status":"CONFIRMED","recordedAt":"$recordedAt","confirmedBy":null,"confirmedAt":null,
               "version":0}"""
        return """
            {
              "transcriptId": "${s.transcriptIds.expected(1)}",
              "residentId": "${s.residentA1}",
              "observations": [
                ${observation(1, """{"type":"BLOOD_PRESSURE","systolic":120,"diastolic":75}""")},
                ${observation(2, """{"type":"PULSE","bpm":80}""")},
                ${observation(3, """{"type":"BODY_TEMPERATURE","celsius":37.2}""")}
              ],
              "rejected": [{"matchedText":"bp 12/10","reason":"systolic=12 is outside the plausible range [50, 260]"}],
              "usedAiFallback": false
            }
            """
    }

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @ParameterizedTest(name = "should return 400 when field is {0}")
        @FieldSource("com.carevoice.documentation.support.DocumentationTestValues#invalidTranscriptJson")
        fun `should return 400 Bad Request when the transcript field is invalid`(raw: String) = withApp { s ->
            client
                .submitTranscript(s, body = withField(Payloads.transcript(), "transcript", raw))
                .assertProblem(PATH, "post", HttpStatusCode.BadRequest, "malformed-request")
        }

        @Test
        fun `should return 400 Bad Request when the body has an unknown field`() = withApp { s ->
            client
                .submitTranscript(s, body = withField(Payloads.transcript(), "trancript", "\"pulse 80\""))
                .assertProblem(PATH, "post", HttpStatusCode.BadRequest, "malformed-request")
        }

        @Test
        fun `should return 400 Bad Request when the body is not valid JSON`() = withApp { s ->
            client
                .submitTranscript(s, body = """{"transcript":""")
                .assertProblem(PATH, "post", HttpStatusCode.BadRequest, "malformed-request")
        }

        @Test
        fun `should return 400 Bad Request when the Idempotency-Key header is missing`() = withApp { s ->
            val problem =
                client
                    .submitTranscript(s, idempotencyKey = null)
                    .assertProblem(PATH, "post", HttpStatusCode.BadRequest, "bad-request")
            assertThat(
                problem["errors"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content,
            ).isEqualTo("Idempotency-Key")
        }

        @ParameterizedTest(name = "should return 400 when field is {0}")
        @FieldSource("com.carevoice.documentation.support.DocumentationTestValues#invalidIdempotencyKeys")
        fun `should return 400 Bad Request when the Idempotency-Key is not a UUID`(key: String) = withApp { s ->
            client
                .submitTranscript(s, idempotencyKey = key)
                .assertProblem(PATH, "post", HttpStatusCode.BadRequest, "bad-request")
        }

        @Test
        fun `should return 404 Not Found when the resident does not exist`() = withApp { s ->
            client
                .submitTranscript(s, residentId = "${s.tenantId}-unknown")
                .assertProblem(PATH, "post", HttpStatusCode.NotFound, "not-found")
        }

        @Test
        fun `should return 409 Conflict when the idempotency key is reused with a different body`() = withApp { s ->
            val key = UUID.randomUUID().toString()
            assertThat(
                client.submitTranscript(s, body = Payloads.pulse(80), idempotencyKey = key).status,
            ).isEqualTo(HttpStatusCode.Created)

            client
                .submitTranscript(s, body = Payloads.pulse(81), idempotencyKey = key)
                .assertProblem(PATH, "post", HttpStatusCode.Conflict, "idempotency-key-reused")
        }

        @Test
        fun `should return 413 Payload Too Large when the body exceeds 64 KiB`() = withApp { s ->
            client
                .submitTranscript(s, body = Payloads.transcript("a".repeat(70_000)))
                .assertProblem(PATH, "post", HttpStatusCode.PayloadTooLarge, "payload-too-large")
        }

        @Test
        fun `should return 415 Unsupported Media Type when the body is not JSON`() = withApp { s ->
            client
                .submitTranscript(s, body = "pulse 80", type = ContentType.Text.Plain)
                .assertProblem(PATH, "post", HttpStatusCode.UnsupportedMediaType, "unsupported-media-type")
        }

        @ParameterizedTest(name = "should return 422 when field is {0}")
        @FieldSource("com.carevoice.documentation.support.DocumentationTestValues#blankTranscripts")
        fun `should return 422 Unprocessable Content when the transcript is blank`(blank: String) = withApp { s ->
            val problem =
                client
                    .submitTranscript(s, body = Payloads.transcript(blank))
                    .assertProblem(PATH, "post", HttpStatusCode.UnprocessableEntity, "validation-error")
            assertThat(
                problem["errors"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content,
            ).isEqualTo("transcript")
        }

        @ParameterizedTest(name = "should return 422 when field is {0} characters long")
        @FieldSource("com.carevoice.documentation.support.DocumentationTestValues#tooLongTranscriptLengths")
        fun `should return 422 Unprocessable Content when the transcript is too long`(length: Int) = withApp { s ->
            client
                .submitTranscript(s, body = Payloads.transcript("a".repeat(length)))
                .assertProblem(PATH, "post", HttpStatusCode.UnprocessableEntity, "validation-error")
        }

        @ParameterizedTest(name = "should return 201 when field is {0} characters long")
        @FieldSource("com.carevoice.documentation.support.DocumentationTestValues#validTranscriptLengths")
        fun `should accept a transcript at the length bounds`(length: Int) = withApp { s ->
            val response = client.submitTranscript(s, body = Payloads.transcript("a".repeat(length)))
            assertThat(response.status).isEqualTo(HttpStatusCode.Created)
            response.assertMatchesSpec(PATH, "post")
        }

        @Test
        fun `should return 429 Too Many Requests with Retry-After when the per-user limit is exhausted`() =
            withApp(TestScenario.create(httpSettings = HttpSettings(rateLimitPerMinute = 1))) { s ->
                assertThat(client.submitTranscript(s).status).isEqualTo(HttpStatusCode.Created)

                val response = client.submitTranscript(s)
                response.assertProblem(PATH, "post", HttpStatusCode.TooManyRequests, "too-many-requests")
                assertThat(response.headers[HttpHeaders.RetryAfter]!!.toLong()).isPositive()
            }

        @Test
        fun `should return 500 without internal details when a port fails unexpectedly`() =
            withApp(TestScenario.create(residents = { ThrowingResidents })) { s ->
                val response = client.submitTranscript(s)
                val problem = response.assertProblem(PATH, "post", HttpStatusCode.InternalServerError, "internal-error")
                assertThat(problem["detail"]!!.jsonPrimitive.content).isEqualTo("An unexpected error occurred.")
                assertThat(response.bodyAsText()).doesNotContain(SECRET_FAILURE)
            }
    }

    @Nested
    @DisplayName("Response Validation")
    inner class ResponseValidation {
        @Test
        fun `should return 201 Created with Location and the full transcript body`() = withApp { s ->
            val response = client.submitTranscript(s, requestId = "req-42")

            assertThat(response.status).isEqualTo(HttpStatusCode.Created)
            assertThat(
                response.headers[HttpHeaders.Location],
            ).isEqualTo("/v1/transcripts/${s.transcriptIds.expected(1)}")
            assertThat(response.headers[HttpHeaders.XRequestId]).isEqualTo("req-42")
            assertThat(response.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
            assertThat(response.headers["X-Content-Type-Options"]).isEqualTo("nosniff")
            assertThat(response.headers["Idempotent-Replayed"]).isNull()
            assertJsonEquals(expectedBody(s), response.assertMatchesSpec(PATH, "post"))
        }

        @Test
        fun `should return 200 with the identical body and Idempotent-Replayed when the request is replayed`() =
            withApp { s ->
                val key = UUID.randomUUID().toString()
                val first = client.submitTranscript(s, idempotencyKey = key).bodyAsText()
                // Time passes between the attempts; the replay must still be the stored body, not a re-computation.
                s.clock.advance(5.minutes)

                val replay = client.submitTranscript(s, idempotencyKey = key)

                assertThat(replay.status).isEqualTo(HttpStatusCode.OK)
                assertThat(replay.headers["Idempotent-Replayed"]).isEqualTo("true")
                assertThat(
                    replay.headers[HttpHeaders.Location],
                ).isEqualTo("/v1/transcripts/${s.transcriptIds.expected(1)}")
                val body = replay.assertMatchesSpec(PATH, "post")
                assertThat(body).isEqualTo(first)
                assertJsonEquals(expectedBody(s), body)
            }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should persist transcript, observations, outbox rows and the audit row`() = withApp { s ->
            client.submitTranscript(s)
            val transcriptId = s.transcriptIds.expected(1)

            assertThat(
                TestDatabase.count("SELECT count(*) FROM transcripts WHERE id = ?", transcriptId),
            ).isEqualTo(1)
            val observations =
                TestDatabase.query(
                    "SELECT id, ward_id, vital_type, status, version FROM observations WHERE transcript_id = ? ORDER BY id",
                    transcriptId,
                )
            assertThat(observations.map { it["id"] }).containsExactly(
                s.observationIds.expected(1),
                s.observationIds.expected(2),
                s.observationIds.expected(3),
            )
            assertThat(
                observations.map {
                    it["vital_type"]
                },
            ).containsExactly("BLOOD_PRESSURE", "PULSE", "BODY_TEMPERATURE")
            assertThat(observations).allSatisfy {
                assertThat(it["ward_id"]).isEqualTo(s.wardA)
                assertThat(it["status"]).isEqualTo("CONFIRMED")
                assertThat(it["version"]).isEqualTo(0)
            }
            assertThat(
                TestDatabase.count(
                    "SELECT count(*) FROM outbox o JOIN observations ob ON ob.id = o.aggregate_id " +
                        "WHERE ob.transcript_id = ? AND o.status = 'PENDING'",
                    transcriptId,
                ),
            ).isEqualTo(3)
            val audit =
                TestDatabase.query(
                    "SELECT tenant_id, actor, action, details::text AS details FROM audit_log WHERE entity_type = 'transcript' AND entity_id = ?",
                    transcriptId.toString(),
                ).single()
            assertThat(audit["tenant_id"]).isEqualTo(s.tenantId)
            assertThat(audit["actor"]).isEqualTo(s.nurse)
            assertThat(audit["action"]).isEqualTo("TRANSCRIPT_SUBMITTED")
            assertJsonEquals(
                """{"residentId":"${s.residentA1}","observationCount":"3","rejectedCount":"1"}""",
                audit["details"] as String,
            )
        }

        @Test
        fun `should store the transcript text encrypted, never as plaintext`() = withApp { s ->
            client.submitTranscript(s)

            val stored =
                TestDatabase.query(
                    "SELECT text_enc FROM transcripts WHERE id = ?",
                    s.transcriptIds.expected(1),
                ).single()["text_enc"] as String
            assertThat(stored).startsWith("v1:").doesNotContain("blood pressure").doesNotContain("Smith")
            assertThat(s.encryptor.decrypt(stored)).isEqualTo(Payloads.FULL_TRANSCRIPT)
        }

        @Test
        fun `should write the contract event as outbox payload with the request id of the submission`() = withApp { s ->
            client.submitTranscript(s, body = Payloads.pulse(80), requestId = "req-outbox-1")
            val observationId = s.observationIds.expected(1)

            val row =
                TestDatabase.query(
                    "SELECT id, event_type, payload::text AS payload, request_id, traceparent FROM outbox WHERE aggregate_id = ?",
                    observationId,
                ).single()
            val expected =
                ObservationRecordedEvent(
                    eventId = s.eventIds.expected(1).toString(),
                    occurredAt = OutboxTestFixtures.FIXED_NOW.toString(),
                    tenantId = s.tenantId,
                    wardId = s.wardA,
                    residentId = s.residentA1,
                    observationId = observationId.toString(),
                    vital = VitalPayload.Pulse(80),
                    source = "RULES",
                    status = "CONFIRMED",
                )
            assertThat(row["id"]).isEqualTo(s.eventIds.expected(1))
            assertThat(row["event_type"]).isEqualTo(ObservationRecordedEvent.TYPE)
            assertJsonEquals(
                ContractJson.encodeToString(ObservationRecordedEvent.serializer(), expected),
                row["payload"] as String,
            )
            assertThat(row["request_id"]).isEqualTo("req-outbox-1")
            // No OpenTelemetry agent in tests: Span.current() is invalid, so no traceparent is stored.
            assertThat(row["traceparent"]).isNull()
        }

        @Test
        fun `should roll back every row when a step inside the transaction fails`() =
            withApp(TestScenario.create(outbox = { FailingOutbox })) { s ->
                client.submitTranscript(
                    s,
                ).assertProblem(PATH, "post", HttpStatusCode.InternalServerError, "internal-error")

                assertThat(
                    TestDatabase.count("SELECT count(*) FROM transcripts WHERE tenant_id = ?", s.tenantId),
                ).isZero()
                assertThat(
                    TestDatabase.count("SELECT count(*) FROM observations WHERE tenant_id = ?", s.tenantId),
                ).isZero()
                assertThat(
                    TestDatabase.count("SELECT count(*) FROM audit_log WHERE tenant_id = ?", s.tenantId),
                ).isZero()
            }

        @Test
        fun `should not store anything twice when the request is replayed`() = withApp { s ->
            val key = UUID.randomUUID().toString()
            client.submitTranscript(s, idempotencyKey = key)
            client.submitTranscript(s, idempotencyKey = key)

            assertThat(
                TestDatabase.count("SELECT count(*) FROM transcripts WHERE tenant_id = ?", s.tenantId),
            ).isEqualTo(1)
            assertThat(
                TestDatabase.count("SELECT count(*) FROM observations WHERE tenant_id = ?", s.tenantId),
            ).isEqualTo(3)
            assertThat(
                TestDatabase.count("SELECT count(*) FROM audit_log WHERE tenant_id = ?", s.tenantId),
            ).isEqualTo(1)
        }

        @Test
        fun `should return the stored body from GET transcripts by id`() = withApp { s ->
            val created = client.submitTranscript(s)
            val location = created.headers[HttpHeaders.Location]!!
            val createdBody = created.bodyAsText()

            val read = client.get(location) { bearerAuth(s.nurseToken()) }

            assertThat(read.status).isEqualTo(HttpStatusCode.OK)
            assertThat(read.assertMatchesSpec("/v1/transcripts/{transcriptId}", "get")).isEqualTo(createdBody)
        }

        @Test
        fun `should count processed transcripts in the business metric`() = withApp { s ->
            client.submitTranscript(s)

            val processed = s.meterRegistry.find(
                "carevoice.transcripts",
            ).tag("outcome", "processed").counter()!!.count()
            assertThat(processed).isEqualTo(1.0)
        }
    }

    private object ThrowingResidents : ResidentRepository {
        override suspend fun findById(tenantId: String, residentId: String): Resident? =
            throw IllegalStateException(SECRET_FAILURE)
    }

    private object FailingOutbox : EventOutbox {
        override suspend fun append(event: ObservationRecordedEvent, correlation: Correlation) =
            throw IllegalStateException(SECRET_FAILURE)
    }

    private companion object {
        const val PATH = "/v1/residents/{residentId}/transcripts"
        const val SECRET_FAILURE = "connection to db-internal-7 refused"
    }
}
