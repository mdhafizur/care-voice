// Pure Kotlin: no framework, no database, no HTTP.
// The services depend on this module. Its only dependency is Arrow's core library (typed errors: Either and the
// either { } builder, ADR-0027); the architecture test allows nothing else here.
//
// Mutation testing (PIT) is planned for this module, but the gradle-pitest-plugin version in the catalog (1.15.0)
// does not support Gradle 9 (it reads the removed ReportingExtension.baseDir). Re-enable it with a Gradle-9-compatible
// release: alias(libs.plugins.pitest) plus a `pitest { targetClasses = listOf("com.carevoice.domain.*") }` block.
plugins {
    id("carevoice.kotlin-library")
    `java-library`
}

dependencies {
    // api, not implementation: Either appears in this module's public signatures, so the services compile against it.
    api(libs.arrow.core)
    testImplementation(project(":test-support"))
}
