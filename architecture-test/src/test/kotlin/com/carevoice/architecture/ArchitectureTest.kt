package com.carevoice.architecture

import com.carevoice.architecture.ArchitectureValues.outboxJdbcPackages
import com.carevoice.architecture.ArchitectureValues.repositoryTechnologyPrefixes
import com.carevoice.architecture.ArchitectureValues.testAnnotations
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoImportDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

/**
 * The layering rules of CONTRACTS sections 2 and 14, checked on the source code of every module.
 *
 * A rule written down only in a document erodes with the first deadline; a failing build does not. Each test names
 * the rule it protects, and Konsist's failure message lists every offending file and line.
 */
@DisplayName("Architecture")
class ArchitectureTest {
    @Nested
    @DisplayName("Domain")
    inner class Domain {
        @Test
        fun `should find the domain sources, so that no rule passes on an empty scope`() {
            domainFiles.assertTrue(strict = true) { it.hasPackage("com.carevoice.domain..") }
        }

        @ParameterizedTest(name = "should not import {0} in the domain")
        @FieldSource("com.carevoice.architecture.ArchitectureValues#frameworkPackages")
        fun `should keep frameworks and drivers out of the domain`(forbiddenPackage: String) {
            domainFiles.assertFalse(additionalMessage = "The domain is pure Kotlin (CONTRACTS section 2).") { file ->
                file.imports.any { it.isIn(forbiddenPackage) }
            }
        }
    }

    @Nested
    @DisplayName("Layers")
    inner class Layers {
        @Test
        fun `should not import infrastructure from the api layer`() {
            production.filesIn("com.carevoice..api..").assertFalse(
                additionalMessage = "Routes go through use cases in application, never to adapters directly.",
            ) { file -> file.imports.any { it.isProjectPackage("infrastructure") } }
        }

        @ParameterizedTest(name = "should import {0} ({1}) only in infrastructure")
        @FieldSource("com.carevoice.architecture.ArchitectureValues#adapterOnlyPackages")
        fun `should import driver libraries only in infrastructure adapters`(
            technology: String,
            driverPackage: String,
        ) {
            serviceFiles
                .filterNot { it.hasPackage("com.carevoice..infrastructure..") }
                // The outbox shares the use case's JDBC transaction; it is the only JDBC user outside infrastructure.
                .filterNot { it.hasPackage("com.carevoice..outbox..") && driverPackage in outboxJdbcPackages }
                // The composition root builds the pools and clients it hands to the adapters (manual injection).
                .filterNot { it.isCompositionRoot() }
                .assertFalse(additionalMessage = "$technology belongs in an adapter under ..infrastructure..") { file ->
                    file.imports.any { it.isIn(driverPackage) }
                }
        }
    }

    @Nested
    @DisplayName("Repositories")
    inner class Repositories {
        @Test
        fun `should declare repository interfaces as ports in the application layer`() {
            repositoryPorts.assertTrue { it.resideInPackage("com.carevoice..application..") }
        }

        @Test
        fun `should implement repository ports only in the infrastructure layer`() {
            repositoryAdapters.assertTrue { it.resideInPackage("com.carevoice..infrastructure..") }
        }

        @Test
        fun `should name every repository adapter after its technology`() {
            repositoryAdapters.assertTrue(
                additionalMessage = "Prefix the adapter with its technology: $repositoryTechnologyPrefixes.",
            ) { adapter -> repositoryTechnologyPrefixes.any { adapter.name.startsWith(it) } }
        }
    }

    @Nested
    @DisplayName("Naming")
    inner class Naming {
        @Test
        fun `should start every test name with should`() {
            Konsist
                .scopeFromTest()
                .classes(includeNested = false)
                .filter { it.name.endsWith("Test") }
                // includeNested: the tests of a test class live in its @Nested inner classes.
                .flatMap { it.functions(includeNested = true, includeLocal = false) }
                .filter { it.hasAnnotationWithName(testAnnotations) }
                .assertTrue(additionalMessage = "Convention: every test name starts with \"should\".") {
                    it.name.startsWith("should")
                }
        }

        @Test
        fun `should not name a class after its implementation status`() {
            everything.classesAndInterfacesAndObjects().assertFalse(
                additionalMessage = "\"Impl\" says nothing; name the variant instead (PostgresResidentRepository).",
            ) { it.name.endsWith("Impl") }
        }
    }

    private companion object {
        // Parsing every source file of the repository takes a moment, so each scope is built once per test run.
        val everything: KoScope by lazy { Konsist.scopeFromProject() }
        val production: KoScope by lazy { Konsist.scopeFromProduction() }

        /**
         * Production code of the modules that run: the domain plus the services. test-support is excluded: it is test
         * infrastructure that starts databases on purpose (it is only ever a testImplementation dependency).
         */
        val serviceFiles: List<KoFileDeclaration> by lazy {
            production.files.filterNot { it.moduleName == "test-support" }
        }

        val domainFiles: List<KoFileDeclaration> by lazy { everything.filesIn("com.carevoice.domain..") }

        val repositoryPorts by lazy { production.interfaces().filter { it.name.endsWith("Repository") } }

        /** Classes implementing (or decorating) a repository port; a class merely named "...Repository" is not one. */
        val repositoryAdapters by lazy {
            val portNames = repositoryPorts.map { it.name }.toSet()
            production.classes().filter { adapter -> adapter.parents().any { it.name.simpleTypeName() in portNames } }
        }

        /** Application.kt directly in a service's base package, e.g. com.carevoice.documentation.Application.kt. */
        fun KoFileDeclaration.isCompositionRoot(): Boolean =
            name == "Application" && packagee?.name?.matches(Regex("""com\.carevoice\.[a-z]+""")) == true

        fun KoScope.filesIn(packagePattern: String): List<KoFileDeclaration> =
            files.filter { it.hasPackage(packagePattern) }

        /** The package itself and every package below it: "java.sql" matches java.sql.Connection, not java.sqlx. */
        fun KoImportDeclaration.isIn(packageName: String): Boolean =
            name == packageName || name.startsWith("$packageName.")

        /** An import of one of our own layers, e.g. com.carevoice.documentation.infrastructure.crypto.AesGcmCipher. */
        fun KoImportDeclaration.isProjectPackage(layer: String): Boolean =
            name.startsWith("com.carevoice.") && ".$layer." in name

        /** "com.x.ResidentRepository<Id>" or "ResidentRepository by delegate" -> "ResidentRepository". */
        fun String.simpleTypeName(): String = substringBefore('<').substringBefore(' ').substringAfterLast('.').trim()
    }
}
