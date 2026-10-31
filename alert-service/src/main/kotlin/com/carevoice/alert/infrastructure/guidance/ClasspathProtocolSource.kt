package com.carevoice.alert.infrastructure.guidance

import com.carevoice.alert.application.ProtocolDocument
import com.carevoice.alert.application.ProtocolSource

/**
 * Protocol documents shipped with the service: `protocols/index.txt` lists the markdown files (a classpath directory
 * cannot be listed reliably inside a jar). Versioned with the code, reviewed in pull requests like code.
 */
class ClasspathProtocolSource(private val basePath: String = "protocols") : ProtocolSource {
    override fun documents(): List<ProtocolDocument> = read("index.txt")
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { file -> ProtocolDocument(id = file.removeSuffix(".md"), markdown = read(file)) }

    private fun read(file: String): String = ClasspathProtocolSource::class.java.classLoader
        .getResource("$basePath/$file")
        ?.readText()
        ?: error("Protocol resource $basePath/$file is missing from the classpath")
}
