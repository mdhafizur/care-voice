import com.adarshr.gradle.testlogger.theme.ThemeType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Convention for every Kotlin module: compiler settings, formatting, coverage gate and test setup.
// A module opts in with `plugins { id("carevoice.kotlin-library") }` and gets the same rules as all others.
plugins {
    id("org.jetbrains.kotlin.jvm")
    // Formatting via ktlint; spotlessCheck runs as part of `check`.
    id("com.diffplug.spotless")
    // Code coverage with enforced minimums; koverVerify runs as part of `check`.
    id("org.jetbrains.kotlinx.kover")
    // Prints test results as a tree: class > nested class > test.
    id("com.adarshr.test-logger")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Build with any recent JDK, emit Java 21 bytecode (the LTS the Docker images run on).
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj").get())
    "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    // Slow tests that need Docker and inject network faults are tagged "fault-injection" and excluded by default;
    // `make test-fault` (-PincludeTags=fault-injection) runs only them.
    val includeTags = providers.gradleProperty("includeTags").orNull
    useJUnitPlatform {
        if (includeTags != null) includeTags(*includeTags.split(",").toTypedArray()) else excludeTags("fault-injection")
    }
    // The test database backend (embedded or containers) is chosen by this variable; pass it through to the forked JVM.
    providers.environmentVariable("CAREVOICE_TEST_DB").orNull?.let { systemProperty("CAREVOICE_TEST_DB", it) }
    // Testcontainers start real databases; give the forked test JVM room and keep failures readable.
    maxHeapSize = "1g"
}

testlogger {
    theme = ThemeType.MOCHA
    showSimpleNames = true
}

spotless {
    // intellij_idea matches IntelliJ's Reformat Code, so the two never undo each other's changes.
    val ktlintStyle = mapOf("ktlint_code_style" to "intellij_idea", "max_line_length" to "120")
    val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
    kotlin { ktlint(ktlintVersion).editorConfigOverride(ktlintStyle) }
    kotlinGradle { ktlint(ktlintVersion).editorConfigOverride(ktlintStyle) }
}

// Modules that hold only test code have nothing of their own to cover.
// Targets: 80 % lines, 65 % branches. A module whose suite is still being written sets a lower floor in its own
// gradle.properties (carevoice.coverage.minLines / minBranches) as a ratchet: raise it with every batch of tests,
// never lower it.
// findProperty, not providers.gradleProperty: only project properties include a subproject's own gradle.properties.
val minLineCoverage = findProperty("carevoice.coverage.minLines")?.toString()?.toInt() ?: 80
val minBranchCoverage = findProperty("carevoice.coverage.minBranches")?.toString()?.toInt() ?: 65

// contracts is mostly kotlinx.serialization-generated code, whose branches are not ours to cover.
val coverageExempt = setOf("test-support", "architecture-test", "load-test", "contracts")

kover {
    reports {
        verify {
            // Minimum coverage; `check` fails below it.
            if (project.name !in coverageExempt) {
                rule {
                    minBound(minLineCoverage)
                    minBound(minBranchCoverage, CoverageUnit.BRANCH)
                }
            }
        }
    }
}
