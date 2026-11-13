package com.carevoice.testsupport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.fail
import java.time.Instant
import java.util.UUID

/**
 * Full-body JSON equality ("assert the response body in full with one toEqual").
 *
 * Fails on a missing key, a wrong value AND an extra key, so a leaked field is caught.
 * Server-generated values are matched with placeholders instead of being ignored:
 *   "<any-uuid>"    any UUID string
 *   "<any-instant>" any ISO-8601 instant
 *   "<any-string>"  any string
 *   "<any-number>"  any number
 * Prefer injecting a fixed Clock and id generator so placeholders are rarely needed.
 */
fun assertJsonEquals(expected: String, actual: String) {
    val differences = mutableListOf<String>()
    compare(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual), "$", differences)
    if (differences.isNotEmpty()) {
        fail<Unit>("JSON bodies differ:\n  " + differences.joinToString("\n  ") + "\n\nactual body:\n$actual")
    }
}

/**
 * Walks both JSON trees side by side and adds one message per difference to [differences].
 * [path] is the JSON path so far (e.g. "$.items[0].id"), so each message says where the difference is.
 */
private fun compare(expected: JsonElement, actual: JsonElement, path: String, differences: MutableList<String>) {
    when {
        expected is JsonPrimitive && expected.isString && expected.content.startsWith("<any-") ->
            if (!matchesPlaceholder(expected.content, actual)) {
                differences +=
                    "$path: expected ${expected.content}, was $actual"
            }

        expected is JsonObject && actual is JsonObject -> {
            (expected.keys - actual.keys).forEach { differences += "$path.$it: missing" }
            (actual.keys - expected.keys).forEach { differences += "$path.$it: unexpected key (leaked field?)" }
            expected.keys.intersect(actual.keys).forEach {
                compare(expected.getValue(it), actual.getValue(it), "$path.$it", differences)
            }
        }

        expected is JsonArray && actual is JsonArray -> {
            if (expected.size !=
                actual.size
            ) {
                differences += "$path: expected ${expected.size} elements, was ${actual.size}"
            }
            expected.zip(actual).forEachIndexed { i, (e, a) -> compare(e, a, "$path[$i]", differences) }
        }

        expected != actual -> differences += "$path: expected $expected, was $actual"
    }
}

/** True when [actual] fits the "<any-...>" placeholder; an unknown placeholder is a test bug and throws. */
private fun matchesPlaceholder(placeholder: String, actual: JsonElement): Boolean {
    if (actual is JsonNull || actual !is JsonPrimitive) return false
    return when (placeholder) {
        "<any-uuid>" -> actual.isString && runCatching { UUID.fromString(actual.content) }.isSuccess
        "<any-instant>" -> actual.isString && runCatching { Instant.parse(actual.content) }.isSuccess
        "<any-string>" -> actual.isString
        "<any-number>" -> !actual.isString && actual.content.toDoubleOrNull() != null
        else -> error("Unknown placeholder $placeholder")
    }
}

/** Replaces the value of [field] in a JSON object literal, or removes it for [InvalidJson.ABSENT]. Used to build negative bodies. */
fun withField(jsonObject: String, field: String, rawJsonValue: String): String {
    val parsed = Json.parseToJsonElement(jsonObject) as JsonObject
    val updated =
        if (rawJsonValue == InvalidJson.ABSENT) {
            parsed - field
        } else {
            parsed + (field to Json.parseToJsonElement(rawJsonValue))
        }
    return JsonObject(updated).toString()
}
