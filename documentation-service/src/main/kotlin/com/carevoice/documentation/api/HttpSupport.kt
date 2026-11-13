package com.carevoice.documentation.api

import com.carevoice.documentation.application.FieldError
import com.carevoice.documentation.application.Page
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import io.ktor.http.formUrlEncode
import io.ktor.http.takeFrom
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Reads a JSON body strictly:
 * - wrong or missing `Content-Type` → 415 (docs/CONTRACTS.md, section 6),
 * - more than [maxBytes] → 413, checked against `Content-Length` first and enforced while reading (a chunked body
 *   without a length cannot sneak past the limit),
 * - malformed JSON, wrong types, unknown fields → 400 (via [SerializationException] in StatusPages).
 */
suspend fun <T> ApplicationCall.receiveJson(serializer: KSerializer<T>, maxBytes: Long): T {
    val type = request.header(HttpHeaders.ContentType)?.let { runCatching { request.contentType() }.getOrNull() }
    if (type == null || !type.match(ContentType.Application.Json)) throw Problems.unsupportedMediaType()
    val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declared != null && declared > maxBytes) throw Problems.payloadTooLarge(maxBytes)

    val channel = receiveChannel()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_SIZE)
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        out.write(buffer, 0, read)
        if (out.size() > maxBytes) throw Problems.payloadTooLarge(maxBytes)
    }
    val text = out.toString(Charsets.UTF_8)
    if (text.isBlank()) throw Problems.badRequest("The request body must not be empty.")
    return ApiJson.decodeFromString(serializer, text)
}

/** The body is read in chunks of 8 KiB. The size only changes how many reads are needed, not the body limit. */
private const val BUFFER_SIZE = 8 * 1024

/**
 * Weak ETag over the exact response bytes: equal bodies, equal tags, on every replica.
 * The `W/` prefix marks a weak tag (RFC 9110, section 8.8.3).
 */
fun weakEtagOf(body: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
    return "W/\"" + HexFormat.of().formatHex(digest, 0, ETAG_BYTES) + "\""
}

/** The tag uses the first 16 of the 32 SHA-256 bytes (32 hex characters), which keeps the header short. */
private const val ETAG_BYTES = 16

/** Strong ETag of a versioned resource: the optimistic-locking version itself. */
fun versionEtag(version: Int): String = "\"$version\""

/**
 * `If-Match` → expected version. Missing → null (the use case answers 428); present but not a version tag of ours →
 * -1, which never matches, so the use case answers 412.
 */
fun ApplicationCall.ifMatchVersion(): Int? {
    val raw = request.header(HttpHeaders.IfMatch)?.trim() ?: return null
    // Only a strong tag (`"3"`) can carry our version; a weak tag (`W/"3"`) or any other text never matches.
    if (!raw.startsWith("\"")) return -1
    return raw.removeSurrounding("\"").toIntOrNull() ?: -1
}

/** True when `If-None-Match` lists [etag] (or `*`); weak comparison, as RFC 9110 requires for GET. */
fun ApplicationCall.ifNoneMatches(etag: String): Boolean {
    val header = request.header(HttpHeaders.IfNoneMatch) ?: return false
    val opaque = etag.removePrefix("W/")
    return header.split(',').map { it.trim() }.any { it == "*" || it.removePrefix("W/") == opaque }
}

/**
 * Responds with JSON plus a weak ETag of the body, or 304 Not Modified when the client already has it.
 * `Cache-Control: private, no-cache`: the client may keep it, but must revalidate (the data is personal).
 */
suspend fun <T> ApplicationCall.respondJsonConditional(serializer: KSerializer<T>, value: T) {
    val body = ApiJson.encodeToString(serializer, value)
    val etag = weakEtagOf(body)
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, "private, no-cache")
    if (ifNoneMatches(etag)) {
        respond(HttpStatusCode.NotModified)
    } else {
        respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
    }
}

/** Writes [value] as a JSON body with the given status. Unlike [respondJsonConditional], it sets no ETag. */
suspend fun <T> ApplicationCall.respondJson(
    serializer: KSerializer<T>,
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(ApiJson.encodeToString(serializer, value), ContentType.Application.Json, status)
}

/**
 * Pagination headers (docs/CONTRACTS.md, section 6): `X-Total-Count` and an RFC 8288 `Link` header with `first`,
 * `next` (when there are more) and `prev` (when not on the first page). Links keep the request's other parameters.
 */
fun ApplicationCall.paginationHeaders(page: Page<*>) {
    response.header(TOTAL_COUNT_HEADER, page.totalCount.toString())
    val links = mutableListOf("<${pageLink(null)}>; rel=\"first\"")
    page.nextCursor?.let { links += "<${pageLink(it)}>; rel=\"next\"" }
    page.prevCursor?.let { links += "<${pageLink(it)}>; rel=\"prev\"" }
    response.header(HttpHeaders.Link, links.joinToString(", "))
}

/** Builds a link to the current path with the same query, but with `cursor` replaced (or removed when null). */
private fun ApplicationCall.pageLink(cursor: String?): String {
    val builder = URLBuilder().takeFrom(request.uri)
    builder.parameters.remove("cursor")
    cursor?.let { builder.parameters.append("cursor", it) }
    val query = builder.parameters.build().formUrlEncode()
    return builder.encodedPath + if (query.isEmpty()) "" else "?$query"
}

const val TOTAL_COUNT_HEADER = "X-Total-Count"
const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
const val IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed"
const val LAST_EVENT_ID_HEADER = "Last-Event-ID"

/** Field-level 400 for a missing or malformed header. */
fun headerError(name: String, message: String): Nothing =
    throw Problems.badRequest("The request has an invalid header.", listOf(FieldError(name, message)))

/** The request path for a log line, cut to [MAX_LOGGED_PATH] characters: the path is user input. */
internal fun ApplicationCall.loggedPath(): String = request.path().take(MAX_LOGGED_PATH)

/** Longest part of the request path, in characters, that goes into a log line. */
private const val MAX_LOGGED_PATH = 256
