package com.carevoice.documentation.application

/** One keyset page. [nextCursor]/[prevCursor] are opaque encoded [PageCursor]s, null when there is no such page. */
data class Page<T>(val items: List<T>, val nextCursor: String?, val prevCursor: String?, val totalCount: Long)

/** What `PageRequest.limit` may be (docs/CONTRACTS.md, section 6). */
object PageLimits {
    const val MIN = 1
    const val MAX = 100
    const val DEFAULT = 20
}

data class PageRequest(val limit: Int = PageLimits.DEFAULT, val cursor: PageCursor? = null) {
    init {
        require(limit in PageLimits.MIN..PageLimits.MAX) { "limit must be in ${PageLimits.MIN}..${PageLimits.MAX}" }
    }
}
