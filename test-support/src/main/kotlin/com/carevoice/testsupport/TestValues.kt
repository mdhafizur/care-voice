package com.carevoice.testsupport

// Shared value buckets for data-driven tests: a negative test draws from a bucket
// instead of hand-picking values inline, so every field is tested against the same full set.
// Use them with JUnit 5:  @ParameterizedTest(name = "{0}") @FieldSource("com.carevoice.testsupport.InvalidJson#string")
// Module-specific buckets (e.g. implausible pulses) live next to the tests that use them.

/**
 * Raw JSON literals for request bodies, one bucket per expected JSON type.
 * Buckets hold type-invalid values only; a non-nullable field adds [nullish] via [required].
 */
object InvalidJson {
    /** The field is left out of the body entirely; JSON has no `undefined`. */
    const val ABSENT = "<absent>"

    @JvmField val nullish = listOf("null", ABSENT)

    @JvmField val string = listOf("true", "false", "0", "1", "1.2", "[]", "{}", """["x"]""", """{"a":"b"}""")

    @JvmField val integer = listOf("true", "false", "1.5", "\"1\"", "\"abc\"", "[]", "{}", "[1]", "9007199254740993")

    @JvmField val number = listOf("true", "false", "\"1.5\"", "\"abc\"", "[]", "{}", "[1.5]")

    @JvmField val boolean = listOf("0", "1", "\"true\"", "\"false\"", "\"yes\"", "[]", "{}")

    @JvmField val array = listOf("true", "0", "\"a\"", "{}", "1.5")

    @JvmField val `object` = listOf("true", "false", "1.2", "0", "1", "-1", "\"not-an-object\"", "[]", "[1,2]")

    @JvmField val uuid =
        string + listOf("\"\"", "\" \"", "\"not-a-uuid\"", "\"123\"", "\"00000000-0000-0000-0000-00000000000g\"")

    /** Reject list for a non-nullable field, e.g. `required(string)`. */
    fun required(vararg buckets: List<String>): List<String> = nullish + buckets.flatMap { it }
}

/** Kotlin string values that validation must reject. */
object InvalidValues {
    @JvmField val emptyStrings = listOf("", " ", "  ", "\n", "\t", "\r", "\n\t\r", "\n\t\r ")

    /** Header and id values that would put spaces, line breaks or markup into a log line or a query. */
    @JvmField val identifier =
        listOf(
            "has space",
            "line\nbreak",
            "carriage\rreturn",
            "<script>",
            "quote\"d",
            "semi;colon",
            "umläut",
            "a".repeat(65),
        ) +
            emptyStrings

    @JvmField val uuid = listOf("not-a-uuid", "123", "00000000-0000-0000-0000-00000000000g", "{}") + emptyStrings
}

/** Kotlin string values that must be accepted, including both bounds. */
object ValidValues {
    @JvmField val identifier = listOf("res-1", "a", "0", "ward-a", "A".repeat(64))

    @JvmField val uuid = listOf("3fa85f64-5717-4562-b3fc-2c963f66afa6", "00000000-0000-0000-0000-000000000001")
}
