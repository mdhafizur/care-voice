plugins {
    id("carevoice.kotlin-service")
}

application {
    mainClass.set("com.carevoice.documentation.ApplicationKt")
    applicationName = "documentation-service"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":contracts"))

    implementation(libs.bundles.ktor.server)
    implementation(libs.java.jwt)
    implementation(libs.ktor.server.sse)
    implementation(libs.bundles.ktor.client)
    implementation(libs.kotlinx.coroutines.core)
    // MDCContext: carries requestId/outboxEventId into the relay's publishing coroutines.
    implementation(libs.kotlinx.coroutines.slf4j)
    // Reads the current trace context for the outbox; the OpenTelemetry Java agent provides the implementation.
    implementation(libs.opentelemetry.api)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.logging)

    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    implementation(libs.caffeine)
    implementation(libs.lettuce)
    implementation(libs.amqp.client)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.bcrypt)
    implementation(libs.micrometer.prometheus)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.kotlin)
    implementation(libs.resilience4j.micrometer)

    testImplementation(project(":test-support"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockk)
    testImplementation(libs.wiremock)
    testImplementation(libs.testcontainers.toxiproxy)
    testImplementation(libs.pact.consumer.junit5)
}

// The Pact consumer test writes the contract here; alert-service verifies it from /pacts.
tasks.withType<Test>().configureEach {
    systemProperty("pact.rootDir", rootProject.layout.projectDirectory.dir("pacts").asFile.absolutePath)
}
