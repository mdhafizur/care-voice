// Convention for libraries with kotlinx.serialization types (e.g. the wire contract between the services).
// The serialization compiler plugin is on build-logic's classpath, so it can only be applied from a convention
// plugin like this one, not with a bare id in a module's build file.
plugins {
    id("carevoice.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}
