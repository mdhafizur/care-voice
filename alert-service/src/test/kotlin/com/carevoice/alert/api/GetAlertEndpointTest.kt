package com.carevoice.alert.api

import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.AlertRuleType
import com.carevoice.alert.domain.AlertStatus
import com.carevoice.alert.domain.Severity
import com.carevoice.alert.domain.Vital
import com.carevoice.alert.support.AlertTestApp
import com.carevoice.alert.support.AuthorizationContract
import com.carevoice.alert.support.TestFixtures
import com.carevoice.alert.support.Tokens
import com.carevoice.testsupport.assertJsonEquals
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("GET /v1/alerts/{alertId}")
class GetAlertEndpointTest : AuthorizationContract {
    override val app: AlertTestApp = AlertTestApp.forClass(javaClass)
    override val specPath = "/v1/alerts/{alertId}"
    override val specMethod = "get"
    override val wrongRoleAuthorization: String = Tokens.bearer(Tokens.token(roles = listOf("NURSE")))

    /** A fresh alert per call (never mutate shared fixtures). */
    private fun alert(id: String = "alert-0001", wardId: String = TestFixtures.WARD) = Alert(
        id = id,
        tenantId = TestFixtures.TENANT,
        wardId = wardId,
        residentId = "res-1",
        observationId = "obs-1",
        eventId = "event-$id",
        rule = AlertRuleType.HYPERTENSIVE_CRISIS,
        severity = Severity.CRITICAL,
        message = CRISIS_MESSAGE,
        vital = Vital.BloodPressure(185, 95),
        status = AlertStatus.OPEN,
        observedAt = Instant.parse("2026-10-25T09:59:00Z"),
        createdAt = TestFixtures.NOW,
        acknowledgedBy = null,
        acknowledgedAt = null,
        version = 0,
    )

    override suspend fun ApplicationTestBuilder.sendWith(authorization: String?): HttpResponse {
        app.alerts.insertAll(listOf(alert()))
        return client.get("/v1/alerts/alert-0001") {
            authorization?.let { header(HttpHeaders.Authorization, it) }
        }
    }

    override suspend fun ApplicationTestBuilder.sendForOtherWardResource(): HttpResponse {
        app.alerts.insertAll(listOf(alert(id = "alert-other", wardId = TestFixtures.OTHER_WARD)))
        return client.get("/v1/alerts/alert-other") { header(HttpHeaders.Authorization, Tokens.wardLead()) }
    }

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @Test
        fun `should return 404 Not Found when the alert does not exist`() = app.run {
            val response =
                client.get("/v1/alerts/does-not-exist") { header(HttpHeaders.Authorization, Tokens.wardLead()) }

            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
            TestFixtures.openApi.assertResponseMatches(
                specPath,
                "get",
                404,
                response.bodyAsText(),
                "application/problem+json",
            )
        }
    }

    @Nested
    @DisplayName("Response Validation")
    inner class ResponseValidation {
        @Test
        fun `should return 200 with the full alert and its version ETag`() = app.run {
            app.alerts.insertAll(listOf(alert()))

            val response =
                client.get("/v1/alerts/alert-0001") { header(HttpHeaders.Authorization, Tokens.wardLead()) }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.ETag]).isEqualTo("\"0\"")
            assertJsonEquals(
                """
                    {
                      "id": "alert-0001",
                      "tenantId": "sunrise-care",
                      "wardId": "ward-a",
                      "residentId": "res-1",
                      "observationId": "obs-1",
                      "eventId": "event-alert-0001",
                      "rule": "HYPERTENSIVE_CRISIS",
                      "severity": "CRITICAL",
                      "message": "$CRISIS_MESSAGE",
                      "vital": {"type": "BLOOD_PRESSURE", "systolic": 185, "diastolic": 95},
                      "status": "OPEN",
                      "observedAt": "2026-10-25T09:59:00Z",
                      "createdAt": "2026-10-25T10:00:00Z",
                      "acknowledgedBy": null,
                      "acknowledgedAt": null,
                      "version": 0
                    }
                """.trimIndent(),
                response.bodyAsText(),
            )
            TestFixtures.openApi.assertResponseMatches(specPath, "get", 200, response.bodyAsText())
        }

        @Test
        fun `should return 304 Not Modified when If-None-Match matches the ETag`() = app.run {
            app.alerts.insertAll(listOf(alert()))

            val response =
                client.get("/v1/alerts/alert-0001") {
                    header(HttpHeaders.Authorization, Tokens.wardLead())
                    header(HttpHeaders.IfNoneMatch, "\"0\"")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.NotModified)
            assertThat(response.bodyAsText()).isEmpty()
            assertThat(TestFixtures.openApi.documentsStatus(specPath, "get", 304)).isTrue()
        }
    }

    private companion object {
        const val CRISIS_MESSAGE =
            "Blood pressure 185/95 mmHg: systolic >= 180 or diastolic >= 120 (hypertensive crisis)."
    }
}
