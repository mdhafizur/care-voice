package com.carevoice.testsupport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.assertj.core.api.Assertions.assertThat

/**
 * Validates request and response bodies against an OpenAPI 3.1 spec on the classpath.
 * OpenAPI 3.1 schemas are JSON Schema 2020-12, so a standard validator can check them.
 *
 * Every integration test passes its responses through [assertResponseMatches], so the spec and the
 * implementation cannot drift apart without a test failing.
 */
class OpenApiContract(specResource: String) {
    private val yaml = ObjectMapper(YAMLFactory())
    private val json = ObjectMapper()
    private val spec: JsonNode =
        yaml.readTree(
            requireNotNull(javaClass.classLoader.getResourceAsStream(specResource)) {
                "Spec $specResource not on classpath"
            },
        )
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    /** Violations of the response schema for [path] (the spec's template, e.g. "/v1/alerts/{alertId}"). */
    fun responseViolations(
        path: String,
        method: String,
        status: Int,
        body: String,
        contentType: String = "application/json",
    ): List<String> {
        val pointer = "${responsePointer(path, method, status)}/content/${escape(contentType)}/schema"
        require(!spec.at(pointer).isMissingNode) {
            "Spec documents no $contentType body for ${method.uppercase()} $path -> $status"
        }
        return validate(pointer, body)
    }

    /** Violations of the request body schema for [path]. */
    fun requestViolations(
        path: String,
        method: String,
        body: String,
        contentType: String = "application/json",
    ): List<String> =
        validate("/paths/${escape(path)}/${method.lowercase()}/requestBody/content/${escape(contentType)}/schema", body)

    /** True when the spec documents [status] for the operation; every status a handler emits must be documented. */
    fun documentsStatus(path: String, method: String, status: Int): Boolean =
        !spec.at("/paths/${escape(path)}/${method.lowercase()}/responses/$status").isMissingNode

    /** Fails the test when the spec does not document [status] or the body does not match its response schema. */
    fun assertResponseMatches(
        path: String,
        method: String,
        status: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        assertThat(documentsStatus(path, method, status))
            .withFailMessage("Spec does not document status $status for ${method.uppercase()} $path")
            .isTrue()
        assertThat(responseViolations(path, method, status, body, contentType))
            .withFailMessage { "Response violates the spec for ${method.uppercase()} $path -> $status" }
            .isEmpty()
    }

    /** Returns the spec part at a JSON pointer (a path into the document, e.g. "/components"), or a missing node. */
    fun at(pointer: String): JsonNode = spec.at(pointer)

    /** Validates [body] against the schema at [pointer] and returns the error messages; empty means valid. */
    private fun validate(pointer: String, body: String): List<String> {
        // A schema document carrying the whole spec (so internal $refs resolve), pointing at the operation's schema.
        val root = (spec.deepCopy<JsonNode>() as ObjectNode).put("\$ref", "#$pointer")
        val schema = factory.getSchema(root)
        return schema.validate(json.readTree(body)).map { it.message }
    }

    /**
     * Where the response object for [status] lives. Specs share error responses through
     * `$ref: '#/components/responses/BadRequest'`, so a response-level reference is followed.
     */
    private fun responsePointer(path: String, method: String, status: Int): String {
        val inline = "/paths/${escape(path)}/${method.lowercase()}/responses/$status"
        val ref = spec.at(inline).get("\$ref")?.asText()
        return if (ref != null && ref.startsWith("#")) ref.removePrefix("#") else inline
    }

    /** Escapes one JSON pointer segment ("~" -> "~0", "/" -> "~1"), so "/v1/alerts" stays one segment. */
    private fun escape(segment: String) = segment.replace("~", "~0").replace("/", "~1")
}
