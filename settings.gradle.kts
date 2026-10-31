rootProject.name = "carevoice"

pluginManagement {
    // Convention plugins (carevoice.kotlin-library, carevoice.kotlin-service) live here.
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Pure domain logic, no framework
include("domain")
// Event and DTO types shared between the services (the wire contract)
include("contracts")
// Test buckets and helpers shared by all test suites
include("test-support")
// Services
include("documentation-service")
include("alert-service")
// Gatling load tests (Kotlin simulations in load-test/src/gatling/kotlin)
include("load-test")
// Architecture rules across all modules (Konsist)
include("architecture-test")
