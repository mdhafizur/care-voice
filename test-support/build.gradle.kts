// Shared test helpers (value buckets, JSON and OpenAPI assertions, Testcontainers singletons).
// Only ever used as a testImplementation dependency.
plugins {
    id("carevoice.kotlin-library")
}

dependencies {
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
    api(libs.assertj)
    api(libs.kotlinx.serialization.json)
    api(libs.json.schema.validator)
    api(libs.jackson.databind)
    api(libs.jackson.yaml)
    api(platform(libs.testcontainers.bom))
    api(libs.testcontainers.junit)
    api(libs.testcontainers.postgresql)
    api(libs.testcontainers.mongodb)
    api(libs.testcontainers.rabbitmq)
    api(libs.embedded.postgres)
    api(libs.embedded.mongo)
    api(libs.embedded.redis)
}
