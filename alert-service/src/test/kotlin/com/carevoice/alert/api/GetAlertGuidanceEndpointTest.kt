package com.carevoice.alert.api

import com.carevoice.alert.application.IngestProtocols
import com.carevoice.alert.domain.AlertStatus
import com.carevoice.alert.domain.WardScope
import com.carevoice.alert.infrastructure.guidance.ClasspathProtocolSource
import com.carevoice.alert.infrastructure.guidance.HashingEmbedder
import com.carevoice.alert.infrastructure.persistence.MongoProtocolChunkRepository
import com.carevoice.alert.support.AlertTestApp
import com.carevoice.alert.support.AuthorizationContract
import com.carevoice.alert.support.GuidanceTestAlerts
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GET /v1/alerts/{alertId}/guidance")
class GetAlertGuidanceEndpointTest : AuthorizationContract {
    override val app: AlertTestApp = AlertTestApp.forClass(javaClass)
    override val specPath = "/v1/alerts/{alertId}/guidance"
    override val specMethod = "get"
    override val wrongRoleAuthorization: String = Tokens.bearer(Tokens.token(roles = listOf("NURSE")))

    /** The shipped protocols, ingested once into this class's Mongo database (exercises the Mongo adapter too). */
    private val knowledgeBase: MongoProtocolChunkRepository by lazy {
        MongoProtocolChunkRepository(app.database).also { chunks ->
            runBlocking { IngestProtocols(ClasspathProtocolSource(), HashingEmbedder(), chunks).execute() }
        }
    }

    private suspend fun ApplicationTestBuilder.getGuidance(alertId: String): HttpResponse =
        client.get("/v1/alerts/$alertId/guidance") { header(HttpHeaders.Authorization, Tokens.wardLead()) }

    override suspend fun ApplicationTestBuilder.sendWith(authorization: String?): HttpResponse {
        app.alerts.insertAll(listOf(GuidanceTestAlerts.alert()))
        return client.get("/v1/alerts/alert-0001/guidance") {
            authorization?.let { header(HttpHeaders.Authorization, it) }
        }
    }

    override suspend fun ApplicationTestBuilder.sendForOtherWardResource(): HttpResponse {
        app.alerts.insertAll(listOf(GuidanceTestAlerts.alert(id = "alert-other", wardId = TestFixtures.OTHER_WARD)))
        return client.get("/v1/alerts/alert-other/guidance") { header(HttpHeaders.Authorization, Tokens.wardLead()) }
    }

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @Test
        fun `should return 404 Not Found when the alert does not exist`() = app.run {
            val response = getGuidance("does-not-exist")

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
        fun `should return 200 with steps that cite only the retrieved protocol sections`() =
            app.run(app.dependencies(protocolChunks = knowledgeBase)) {
                app.alerts.insertAll(listOf(GuidanceTestAlerts.alert()))

                val response = getGuidance("alert-0001")

                assertThat(response.status).isEqualTo(HttpStatusCode.OK)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val sourceIds =
                    body.getValue("sources").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
                val citations =
                    body.getValue("steps").jsonArray.flatMap { step ->
                        step.jsonObject.getValue("citations").jsonArray.map { it.jsonPrimitive.content }
                    }
                assertThat(body.getValue("status").jsonPrimitive.content).isEqualTo("FOUND")
                assertThat(body.getValue("generatedBy").jsonPrimitive.content).isEqualTo("EXTRACTIVE")
                assertThat(sourceIds).containsExactly(
                    "hypertensive-crisis#1",
                    "hypertensive-crisis#2",
                    "hypertensive-crisis#3",
                    "hypertensive-crisis#4",
                )
                assertThat(citations).isNotEmpty().allMatch { it in sourceIds }
                TestFixtures.openApi.assertResponseMatches(specPath, "get", 200, response.bodyAsText())
            }

        @Test
        fun `should return 200 with NO_MATCHING_PROTOCOL and empty lists when no knowledge base is configured`() =
            app.run {
                app.alerts.insertAll(listOf(GuidanceTestAlerts.alert()))

                val response = getGuidance("alert-0001")

                assertThat(response.status).isEqualTo(HttpStatusCode.OK)
                assertJsonEquals(
                    """
                    {
                      "alertId": "alert-0001",
                      "rule": "HYPERTENSIVE_CRISIS",
                      "status": "NO_MATCHING_PROTOCOL",
                      "generatedBy": null,
                      "steps": [],
                      "sources": [],
                      "disclaimer": "${AlertDtoMapper.GUIDANCE_DISCLAIMER}"
                    }
                    """.trimIndent(),
                    response.bodyAsText(),
                )
                TestFixtures.openApi.assertResponseMatches(specPath, "get", 200, response.bodyAsText())
            }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should not change the alert, because guidance is advisory only`() =
            app.run(app.dependencies(protocolChunks = knowledgeBase)) {
                app.alerts.insertAll(listOf(GuidanceTestAlerts.alert()))

                getGuidance("alert-0001")

                val stored = app.alerts.findById(WardScope(TestFixtures.TENANT, TestFixtures.WARD), "alert-0001")!!
                assertThat(stored.status).isEqualTo(AlertStatus.OPEN)
                assertThat(stored.version).isZero()
            }
    }
}
