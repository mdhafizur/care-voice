package com.carevoice.documentation.support

import com.carevoice.testsupport.OpenApiContract
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

/** The service's own OpenAPI spec; every integration test checks its responses against it. */
object Api {
    val contract = OpenApiContract("openapi/documentation-service.yaml")
    val PROBLEM_JSON: ContentType = ContentType("application", "problem+json")
}

/** Fresh request bodies per call (never a shared, mutated fixture). */
object Payloads {
    /**
     * Blood pressure, pulse and temperature (all explicit, so CONFIRMED with confidence 0.95) plus one implausible
     * value that ends up in `rejected`.
     */
    const val FULL_TRANSCRIPT =
        "Mrs. Smith, blood pressure 120 over 75, pulse 80, temperature 37.2 degrees, earlier bp 12/10"

    fun transcript(text: String = FULL_TRANSCRIPT): String = buildJsonObject { put("transcript", text) }.toString()

    fun pulse(bpm: Int): String = transcript("pulse $bpm")
}

/**
 * Validates the response against the spec (status documented, body matches the schema for its content type) and
 * returns the body. A 304 has no body: only that the spec documents it is checked.
 */
suspend fun HttpResponse.assertMatchesSpec(path: String, method: String): String {
    val body = bodyAsText()
    if (status == HttpStatusCode.NotModified) {
        assertThat(Api.contract.documentsStatus(path, method, status.value))
            .withFailMessage("Spec does not document 304 for ${method.uppercase()} $path")
            .isTrue()
        assertThat(body).isEmpty()
        return body
    }
    val type = contentType()?.withoutParameters()?.toString() ?: "application/json"
    Api.contract.assertResponseMatches(path, method, status.value, body, type)
    return body
}

/**
 * Asserts an RFC 9457 problem response: status, `application/problem+json`, the problem type slug, the spec, and
 * that `requestId` in the body equals the `X-Request-Id` header. Returns the parsed body for further checks.
 */
suspend fun HttpResponse.assertProblem(
    path: String,
    method: String,
    expected: HttpStatusCode,
    slug: String,
): JsonObject {
    assertThat(status).isEqualTo(expected)
    assertThat(contentType()?.withoutParameters()).isEqualTo(Api.PROBLEM_JSON)
    val body = bodyAsText()
    Api.contract.assertResponseMatches(path, method, status.value, body, Api.PROBLEM_JSON.toString())
    val json = Json.parseToJsonElement(body).jsonObject
    assertThat(json["type"]?.jsonPrimitive?.content).isEqualTo("https://carevoice.dev/problems/$slug")
    assertThat(json["status"]?.jsonPrimitive?.content).isEqualTo(expected.value.toString())
    assertThat(json["requestId"]?.jsonPrimitive?.content).isNotBlank().isEqualTo(headers[HttpHeaders.XRequestId])
    return json
}

/** RFC 8288 `Link` header → rel to URL (path and query, as the service writes them). */
fun parseLinks(header: String?): Map<String, String> = header
    .orEmpty()
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .associate { part ->
        part.substringAfter("rel=\"").substringBefore('"') to
            part.substringAfter('<').substringBefore('>')
    }

/** `POST /v1/residents/{residentId}/transcripts` with sensible defaults; every argument can be overridden. */
suspend fun HttpClient.submitTranscript(
    scenario: TestScenario,
    body: String = Payloads.transcript(),
    residentId: String = scenario.residentA1,
    idempotencyKey: String? = UUID.randomUUID().toString(),
    token: String? = scenario.nurseToken(),
    requestId: String? = null,
    type: ContentType = ContentType.Application.Json,
): HttpResponse = post("/v1/residents/$residentId/transcripts") {
    token?.let { bearerAuth(it) }
    idempotencyKey?.let { header("Idempotency-Key", it) }
    requestId?.let { header(HttpHeaders.XRequestId, it) }
    contentType(type)
    setBody(body)
}

/** `GET /v1/residents/{residentId}/observations`; [params] are URL-encoded query parameters (limit, cursor). */
suspend fun HttpClient.listObservations(
    scenario: TestScenario,
    residentId: String = scenario.residentA1,
    params: Map<String, String> = emptyMap(),
    token: String? = scenario.nurseToken(),
    ifNoneMatch: String? = null,
): HttpResponse = get("/v1/residents/$residentId/observations") {
    params.forEach { (name, value) -> parameter(name, value) }
    token?.let { bearerAuth(it) }
    ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
}

/** Follows a URL taken from a `Link` header. */
suspend fun HttpClient.follow(url: String, token: String): HttpResponse = get(url) { bearerAuth(token) }
