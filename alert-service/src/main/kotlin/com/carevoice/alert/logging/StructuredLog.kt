package com.carevoice.alert.logging

import org.slf4j.Logger
import org.slf4j.MDC
import org.slf4j.event.Level

/**
 * Log types from docs/CONTRACTS.md, section 10: the `type` field of the JSON log line, so dashboards can filter "all
 * calls to other services" or "all database work" without parsing messages. Same values as documentation-service.
 */
enum class LogType(val value: String) {
    REQUEST("request"),
    DB_OPERATION("dboperation"),
    EXTERNAL_REQUEST("externalrequest"),
    BUSINESS("business"),
}

/**
 * Logs [message] with [fields] as structured key-value pairs (SLF4J 2 fluent API), the same format as
 * documentation-service: logstash-logback-encoder writes them as top-level JSON fields, and the OpenTelemetry agent
 * copies them onto the log record, so they are filterable in Loki instead of buried in the message text.
 *
 * [type] goes through the MDC rather than as a key-value pair: alert-service already keeps `type` in the MDC (`request`
 * for the access log, `business` while an event is processed), and one key in both places would produce two `type`
 * fields in one JSON line. With [type] null the surrounding MDC type stays.
 *
 * Only ids, counts, durations, names and status codes go into [fields]: never payloads, protocol prompts, tokens or
 * personal data.
 */
fun Logger.event(
    level: Level,
    type: LogType?,
    message: String,
    vararg fields: Pair<String, Any?>,
    cause: Throwable? = null,
) {
    // Step 1: stop early when this level is off, so no work is done for a line nobody sees.
    if (!isEnabledForLevel(level)) return
    // Step 2: put the log type into the MDC (values added to every log line), and remember
    // the old value.
    val previousType = MDC.get(TYPE_KEY)
    if (type != null) MDC.put(TYPE_KEY, type.value)
    try {
        // Step 3: add the fields and the error, then write the line.
        var builder = atLevel(level)
        for ((key, value) in fields) builder = builder.addKeyValue(key, value)
        if (cause != null) builder = builder.setCause(cause)
        builder.log(message)
    } finally {
        // Step 4: put the old log type back, so the caller's MDC is unchanged.
        if (type != null) {
            if (previousType == null) MDC.remove(TYPE_KEY) else MDC.put(TYPE_KEY, previousType)
        }
    }
}

private const val TYPE_KEY = "type"
