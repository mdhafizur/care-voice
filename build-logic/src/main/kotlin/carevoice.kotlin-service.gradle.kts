// Convention for runnable services: everything from the library convention, plus an application
// entry point (installDist produces build/install/<name>, which the Dockerfile copies), JSON
// serialization and an SBOM of the runtime dependencies for vulnerability scanning.
plugins {
    id("carevoice.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
    id("org.cyclonedx.bom")
}

// Only what ships in the image.
tasks.cyclonedxDirectBom {
    includeConfigs = listOf("runtimeClasspath")
}
