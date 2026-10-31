// Pure Kotlin: no framework, no database, no HTTP.
// The services depend on this module; this module depends on nothing (hexagonal architecture).
//
// Mutation testing (PIT) is planned for this module, but the gradle-pitest-plugin version in the catalog (1.15.0)
// does not support Gradle 9 (it reads the removed ReportingExtension.baseDir). Re-enable it with a Gradle-9-compatible
// release: alias(libs.plugins.pitest) plus a `pitest { targetClasses = listOf("com.carevoice.domain.*") }` block.
plugins {
    id("carevoice.kotlin-library")
}

dependencies {
    testImplementation(project(":test-support"))
}
