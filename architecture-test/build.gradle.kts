// Architecture rules (CONTRACTS sections 2 and 14) checked with Konsist across every module of the repository.
// Test sources only: the module has no production code of its own.
plugins {
    id("carevoice.kotlin-library")
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(project(":test-support"))
}

// Konsist parses the source files of ALL modules, which Gradle does not know about: without declaring them as inputs,
// a change in documentation-service alone would leave this test task UP-TO-DATE and the rules would silently not run.
val projectSources =
    rootProject.fileTree(rootDir) {
        include("**/*.kt")
        exclude("**/build/**", "**/.gradle/**")
    }

tasks.test {
    inputs
        .files(projectSources)
        .withPropertyName("projectSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
