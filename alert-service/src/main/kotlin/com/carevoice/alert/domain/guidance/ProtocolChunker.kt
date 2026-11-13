package com.carevoice.alert.domain.guidance

/**
 * Splits a markdown protocol into chunks, one per `##` section.
 *
 * Why sections and not fixed-size windows: a protocol section is a self-contained instruction ("When to escalate"),
 * which is exactly the unit a ward lead needs and the unit an answer should cite. Fixed windows would cut steps in
 * half and make citations meaningless. Sections longer than [MAX_CHARS] are split at blank lines, so one oversized
 * section cannot dominate the context window. Text before the first `##` (title line, notices) is not chunked.
 */
object ProtocolChunker {
    const val MAX_CHARS = 1_200

    /**
     * Returns one chunk per `##` section (or more for long sections), numbered `<documentId>#1`, `#2`, ...
     * Each chunk text starts with `<title>. <section>.` so the search also matches the protocol and section names.
     * Throws when the id is not lowercase kebab-case (words joined by `-`) or the document has no `# ` title.
     */
    fun chunk(documentId: String, markdown: String): List<ProtocolChunk> {
        // Step 1: check the id and find the `# ` title, which every chunk text will start with.
        require(documentId.matches(Regex("[a-z0-9-]+"))) { "documentId must be lowercase kebab-case: $documentId" }
        val lines = markdown.lines()
        val title =
            lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                ?: throw IllegalArgumentException("Protocol $documentId has no '# ' title line")

        // Step 2: group the lines by `##` heading. Lines before the first `##` belong to no section and are dropped.
        val sections = mutableListOf<Pair<String, MutableList<String>>>()
        for (line in lines) {
            when {
                line.startsWith("## ") -> sections += line.removePrefix("## ").trim() to mutableListOf()
                sections.isNotEmpty() -> sections.last().second += line
            }
        }

        // Step 3: split long sections at blank lines and number all chunks of the document 1, 2, 3, ...
        var index = 0
        return sections.flatMap { (section, body) ->
            split(body.joinToString("\n").trim()).map { part ->
                index++
                ProtocolChunk(
                    id = "$documentId#$index",
                    documentId = documentId,
                    documentTitle = title,
                    section = section,
                    text = "$title. $section.\n$part",
                )
            }
        }
    }

    /** Splits [body] at blank lines into parts of at most [MAX_CHARS] (a single longer paragraph stays whole). */
    private fun split(body: String): List<String> {
        if (body.isEmpty()) return emptyList()
        if (body.length <= MAX_CHARS) return listOf(body)
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (paragraph in body.split(Regex("\n\\s*\n"))) {
            if (current.isNotEmpty() && current.length + paragraph.length + 2 > MAX_CHARS) {
                parts += current.toString().trim()
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
        }
        if (current.isNotBlank()) parts += current.toString().trim()
        return parts
    }
}
