// Shared build configuration lives in the convention plugins under build-logic/; each module applies the one it needs.
//
// Declaring them here with `apply false` loads build-logic (and the plugins it brings: Kotlin, Spotless, Kover,
// test-logger) once, in the root classloader that every module inherits. Without it, a module that also applies a
// plugin from the plugin portal (load-test with Gatling) gets its own copy of those classes, and shared build
// services such as Spotless's then clash between sibling projects.
plugins {
    id("carevoice.kotlin-library") apply false
    id("carevoice.kotlin-serialization") apply false
    id("carevoice.kotlin-service") apply false
}
