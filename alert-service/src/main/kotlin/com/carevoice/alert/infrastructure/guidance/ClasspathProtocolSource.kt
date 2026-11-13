package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.ProtocolDocument
import com.carevoice.alert.application.ProtocolSource

/**
 * Protocol documents shipped with the service: `protocols/index.txt` lists the markdown files (a classpath directory
 * cannot be listed reliably inside a jar). Versioned with the code, reviewed in pull requests like code.
 */
class ClasspathProtocolSource(private val basePath: String = "protocols") : ProtocolSource {
    /**
     * Loads every file listed in index.txt; empty lines and lines starting with # are skipped.
     * The document id is the file name without .md.
     */
    override fun documents(): List<ProtocolDocument> = read("index.txt")
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { file -> ProtocolDocument(id = file.removeSuffix(".md"), markdown = read(file)) }

    /** Reads one resource under [basePath]; fails when the file is missing from the classpath. */
    private fun read(file: String): String = ClasspathProtocolSource::class.java.classLoader
        .getResource("$basePath/$file")
        ?.readText()
        ?: error("Protocol resource $basePath/$file is missing from the classpath")
}
