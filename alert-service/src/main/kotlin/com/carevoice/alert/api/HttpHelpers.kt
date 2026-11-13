package com.carevoice.alert.api

import com.carevoice.alert.application.VersionPrecondition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.security.MessageDigest
import java.util.Base64

/** Maximum request body (docs/CONTRACTS.md, section 6). */
const val MAX_BODY_BYTES: Long = 64L * 1024

/**
 * Reads a JSON body as text, enforcing media type (415) and size (413).
 *
 * The size is checked twice: up front from `Content-Length` (cheap rejection without reading anything) and while
 * reading, at most [MAX_BODY_BYTES] + 1 bytes, so a chunked body without `Content-Length` cannot make the server
 * buffer an arbitrary amount of memory.
 */
suspend fun ApplicationCall.receiveJsonText(): String {
    requireJsonContentType()
    val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declared != null && declared > MAX_BODY_BYTES) throw ApiException.PayloadTooLarge(MAX_BODY_BYTES)
    val bytes = receiveChannel().readRemaining(MAX_BODY_BYTES + 1).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) throw ApiException.PayloadTooLarge(MAX_BODY_BYTES)
    return String(bytes, Charsets.UTF_8)
}

/** Throws 415 (Unsupported Media Type) unless the body is declared as JSON; parameters like charset are ignored. */
fun ApplicationCall.requireJsonContentType() {
    val type = runCatching { request.contentType() }.getOrNull()
    if (type == null || !type.withoutParameters().match(ContentType.Application.Json)) {
        throw ApiException.UnsupportedMediaType()
    }
}

/**
 * Responds with a JSON body and a validator. When `If-None-Match` matches [etag], answers 304 without a body, so
 * a polling client re-downloads the alert list only when it changed.
 */
suspend fun ApplicationCall.respondJsonWithETag(
    json: String,
    etag: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    cacheable: Boolean = true,
) {
    response.header(HttpHeaders.ETag, etag)
    // Revalidation is allowed (private, must ask the server every time); storing in shared caches is not.
    response.header(HttpHeaders.CacheControl, if (cacheable) "private, no-cache" else "no-store")
    if (status == HttpStatusCode.OK && ifNoneMatchMatches(request.header(HttpHeaders.IfNoneMatch), etag)) {
        respond(HttpStatusCode.NotModified)
        return
    }
    respond(TextContent(json, ContentType.Application.Json, status))
}

/** Weak ETag of a response body: same body, same tag, on every pod (no per-instance state involved). */
fun weakETagOf(body: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
    return "W/\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(18)) + "\""
}

/** Strong ETag carrying the resource version, e.g. `"3"`; what `If-Match` must echo. */
fun versionETag(version: Long): String = "\"$version\""

/** Weak comparison (RFC 9110, 13.1.2): `W/` prefixes are ignored; `*` matches anything. */
fun ifNoneMatchMatches(header: String?, etag: String): Boolean {
    if (header.isNullOrBlank()) return false
    val target = etag.removePrefix("W/")
    return header.split(',').map { it.trim() }.any { it == "*" || it.removePrefix("W/") == target }
}

/**
 * Parses `If-Match` into a [VersionPrecondition]; null when the header is absent (→ 428). Strong comparison: weak
 * tags and anything that is not a quoted version never match (→ 412).
 */
fun parseIfMatch(header: String?): VersionPrecondition? {
    if (header == null) return null
    val entries = header.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (entries.any { it == "*" }) return VersionPrecondition.AnyVersion
    val versions =
        entries
            .mapNotNull { entry -> VERSION_TAG.matchEntire(entry)?.groupValues?.get(1)?.toLongOrNull() }
            .toSet()
    return VersionPrecondition.OneOf(versions)
}

private val VERSION_TAG = Regex("^\"(\\d{1,18})\"$")
