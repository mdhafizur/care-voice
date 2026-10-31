package com.carevoice.documentation.support

import com.carevoice.testsupport.InvalidJson
import com.carevoice.testsupport.InvalidValues
import java.util.Base64

/**
 * documentation-service value buckets: data-driven negatives draw from here, never from inline
 * lists. Use with `@FieldSource("com.carevoice.documentation.support.DocumentationTestValues#<field>")`.
 */
object DocumentationTestValues {
    private fun base64Url(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    /** Raw JSON values the `transcript` field must reject with 400 (wrong type, null, absent). */
    @JvmField val invalidTranscriptJson: List<String> = InvalidJson.required(InvalidJson.string)

    /** Well-formed strings the domain rejects with 422 (blank). */
    @JvmField val blankTranscripts: List<String> = InvalidValues.emptyStrings

    /** Lengths above the 2000-character limit (422). */
    @JvmField val tooLongTranscriptLengths: List<Int> = listOf(2_001, 2_500, 10_000)

    /** Positive twin of [tooLongTranscriptLengths], including both bounds. */
    @JvmField val validTranscriptLengths: List<Int> = listOf(1, 2_000)

    /**
     * `Idempotency-Key` values that are not a canonical UUID. Values with line breaks are left out on purpose: the
     * HTTP client refuses to send them, so they could never reach the server.
     */
    @JvmField val invalidIdempotencyKeys: List<String> =
        listOf(
            "not-a-uuid",
            "123",
            "00000000-0000-0000-0000-00000000000g",
            "{}",
            "1-1-1-1-1",
            "3fa85f64-5717-4562-b3fc-2c963f66afa",
            "3fa85f64-5717-4562-b3fc-2c963f66afa6-00",
        )

    /** `?limit=` values outside 1..100 or not an integer (400). */
    @JvmField val invalidLimits: List<String> = listOf("0", "101", "-1", "abc", "1.5", "", "99999999999")

    /** Positive twin of [invalidLimits]: both bounds. */
    @JvmField val validLimits: List<String> = listOf("1", "100")

    /** Cursors that cannot be decoded (400), as opposed to well-formed cursors that match no row (first page). */
    @JvmField val malformedCursors: List<String> =
        listOf(
            "!!!",
            "not base64",
            base64Url("x|y|z"),
            base64Url("a|2026-10-25T10:00:00Z"),
            base64Url("c|2026-10-25T10:00:00Z|3fa85f64-5717-4562-b3fc-2c963f66afa6"),
            base64Url("a|not-an-instant|3fa85f64-5717-4562-b3fc-2c963f66afa6"),
            base64Url("a|2026-10-25T10:00:00Z|not-a-uuid"),
            "a".repeat(201),
        )
}
