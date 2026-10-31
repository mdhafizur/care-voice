// Gatling load tests (CONTRACTS section 2: module load-test, package com.carevoice.loadtest, Kotlin DSL).
// The Gatling plugin creates the `gatling` source set (src/gatling/kotlin, src/gatling/resources) when a Kotlin
// plugin is applied BEFORE it, and adds io.gatling.highcharts:gatling-charts-highcharts to it.
//
//   ./gradlew :load-test:gatlingRun --simulation=com.carevoice.loadtest.SmokeSimulation -DbaseUrl=http://localhost:8080
//   (or: make load-smoke / make load / make load-stress)
plugins {
    // Kotlin JVM (from the build-logic classpath), Java 21 bytecode, ktlint via Spotless. Applying the convention is
    // also what puts the Kotlin Gradle plugin on this project's classpath: a bare id("org.jetbrains.kotlin.jvm")
    // without a version only resolves where a build-logic plugin was applied. It does not touch Gatling's source set;
    // the Kover gate skips this module (coverageExempt in carevoice.kotlin-library). Must come before Gatling, which
    // only creates src/gatling/kotlin when a Kotlin plugin is already applied.
    id("carevoice.kotlin-library")
    alias(libs.plugins.gatling)
}

// Settings passed on the Gradle command line as -Dname=value are forwarded explicitly to the forked Gatling JVM.
val forwardedProperties = listOf("baseUrl", "users", "durationSeconds", "rampSeconds", "maxUsersPerSec", "demoPassword")

gatling {
    jvmArgs = listOf("-server", "-Xms512M", "-Xmx1G", "-XX:+UseG1GC")
    systemProperties =
        forwardedProperties
            .mapNotNull { name -> providers.systemProperty(name).orNull?.let { name to it } }
            .toMap()
}
