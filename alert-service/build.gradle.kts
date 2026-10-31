plugins {
    id("carevoice.kotlin-service")
}

application {
    mainClass.set("com.carevoice.alert.ApplicationKt")
    applicationName = "alert-service"
}

dependencies {
    implementation(project(":contracts"))

    implementation(libs.bundles.ktor.server)
    implementation(libs.java.jwt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.opentelemetry.api)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.logging)

    implementation(libs.mongodb.kotlin.coroutine)
    implementation(libs.amqp.client)
    implementation(libs.mongodb.bson.kotlinx)
    implementation(libs.micrometer.prometheus)
    // Guidance providers (Voyage embeddings, Claude); used only by infrastructure/guidance adapters.
    implementation(libs.bundles.ktor.client)

    testImplementation(project(":test-support"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockk)
    testImplementation(libs.pact.provider.junit5)
}

// Pact provider verification reads the consumer contract written by documentation-service.
tasks.withType<Test>().configureEach {
    systemProperty("pact.rootDir", rootProject.layout.projectDirectory.dir("pacts").asFile.absolutePath)
}
