package com.carevoice.documentation.api.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.response.header

/**
 * Security headers on every response (docs/CONTRACTS.md, section 11). `Cache-Control: no-store` unless a handler
 * set its own (conditional GETs use `private, no-cache`: cacheable by the client, but always revalidated).
 * No CORS plugin on purpose: clients are servers and mobile apps, never browsers on another origin.
 */
fun Application.installSecurityHeaders() {
    install(DefaultHeaders) {
        // nosniff: a browser must use the Content-Type that was sent and never guess another one.
        header("X-Content-Type-Options", "nosniff")
        // no-referrer: a browser that follows a link from a response sends no `Referer` header.
        header("Referrer-Policy", "no-referrer")
    }
    install(
        createApplicationPlugin("CacheControlDefault") {
            onCallRespond { call, _ ->
                if (call.response.headers[HttpHeaders.CacheControl] == null) {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                }
            }
        },
    )
}
