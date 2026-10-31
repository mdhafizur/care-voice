plugins {
    `kotlin-dsl`
}

// Plugins the conventions apply. Putting them on this classpath (instead of applying them with a version in
// each module) loads the Kotlin Gradle plugin exactly once, which avoids "loaded multiple times" errors.
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation("org.jetbrains.kotlin:kotlin-serialization:${libs.versions.kotlin.asProvider().get()}")
    implementation(libs.kover.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.test.logger.gradle.plugin)
    implementation("org.cyclonedx:cyclonedx-gradle-plugin:${libs.versions.cyclonedx.get()}")
}
