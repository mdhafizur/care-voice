// Convention for runnable services: everything from the library convention, plus an application
// entry point (installDist produces build/install/<name>, which the Dockerfile copies), JSON
// serialization and an SBOM of the runtime dependencies for vulnerability scanning.
plugins {
    id("carevoice.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
    id("org.cyclonedx.bom")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // Netty arrives through Ktor, Lettuce and the Mongo driver at whatever version each was built with. The BOM raises
    // every Netty module to one patched version (CVE-2026-75595 in netty-handler 4.2.4, found by the CI Trivy scan).
    "implementation"(platform(libs.findLibrary("netty-bom").get()))
}

// Only what ships in the image.
tasks.cyclonedxDirectBom {
    includeConfigs = listOf("runtimeClasspath")
}
