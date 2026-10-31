// The wire contract between the services: event payloads both sides compile against.
plugins {
    id("carevoice.kotlin-serialization")
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(project(":test-support"))
}
