package com.carevoice.alert.api

import com.carevoice.alert.domain.AlertStatus
import io.ktor.http.encodeURLParameter

/** RFC 8288 `Link` with `first`, `next` (when more) and `prev` (when not on the first page). */
internal fun linkHeader(status: AlertStatus?, limit: Int, nextCursor: String?, prevCursor: String?): String {
    // Builds one page link; null means the first page, and the cursor is URL-encoded.
    fun url(cursor: String?): String = buildString {
        append("/v1/alerts?limit=").append(limit)
        status?.let { append("&status=").append(it.name) }
        cursor?.let { append("&cursor=").append(it.encodeURLParameter()) }
    }
    return buildList {
        add("<${url(null)}>; rel=\"first\"")
        nextCursor?.let { add("<${url(it)}>; rel=\"next\"") }
        prevCursor?.let { add("<${url(it)}>; rel=\"prev\"") }
    }.joinToString(", ")
}
