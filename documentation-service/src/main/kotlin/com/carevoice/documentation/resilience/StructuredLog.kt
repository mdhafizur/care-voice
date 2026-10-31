package com.carevoice.documentation.resilience

import org.slf4j.Logger
import org.slf4j.event.Level

/**
 * Log types from docs/CONTRACTS.md, section 10. They become the `type` field of the JSON log line, so dashboards can
 * filter "all calls to other services" or "all database work" without parsing messages.
 */
enum class LogType(val value: String) {
    REQUEST("request"),
    DB_OPERATION("dboperation"),
    EXTERNAL_REQUEST("externalrequest"),
    BUSINESS("business"),
}

/**
 * Logs [message] with [type] and [fields] as structured key-value pairs (SLF4J 2 fluent API). logstash-logback-encoder
 * renders key-value pairs as top-level JSON fields, so they are queryable in Loki instead of buried in the message.
 *
 * Only ids, counts, durations and status codes go into [fields]: never payloads, transcript text, tokens or names.
 */
fun Logger.event(
    level: Level,
    type: LogType,
    message: String,
    vararg fields: Pair<String, Any?>,
    cause: Throwable? = null,
) {
    if (!isEnabledForLevel(level)) return
    var builder = atLevel(level).addKeyValue("type", type.value)
    for ((key, value) in fields) builder = builder.addKeyValue(key, value)
    if (cause != null) builder = builder.setCause(cause)
    builder.log(message)
}
