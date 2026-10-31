package com.carevoice.documentation.resilience

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * Parses the HTTP `Retry-After` header (RFC 9110, section 10.2.3), which is either a number of seconds (`120`) or an
 * HTTP date (`Wed, 21 Oct 2026 07:28:00 GMT`).
 */
object RetryAfter {
    /**
     * Upper bound for a server-requested wait. A misconfigured or hostile server must not be able to park an outbox
     * row for days with `Retry-After: 99999999`.
     */
    val MAX: Duration = 10.minutes

    /**
     * @return the requested wait, clamped to `[0, MAX]`, or null when the header is absent or unparseable (callers
     *   then fall back to their own backoff instead of failing).
     */
    fun parse(headerValue: String?, now: Instant): Duration? {
        val value = headerValue?.trim()
        if (value.isNullOrEmpty()) return null
        if (value.all { it.isDigit() }) {
            // Huge digit strings overflow toLongOrNull; they mean "very long", which the clamp turns into MAX.
            return (value.toLongOrNull()?.seconds ?: MAX).coerceAtMost(MAX)
        }
        val date =
            try {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            } catch (_: DateTimeParseException) {
                return null
            }
        return java.time.Duration
            .between(now, date)
            .toKotlinDuration()
            .coerceIn(Duration.ZERO, MAX)
    }
}
