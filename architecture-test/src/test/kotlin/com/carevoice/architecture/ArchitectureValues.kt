package com.carevoice.architecture

import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments

// Buckets for the architecture rules: the libraries and packages each rule is about are named once,
// here, so adding a technology means adding one line instead of another test.

object ArchitectureValues {
    /** Frameworks and drivers the domain must never see (CONTRACTS section 2): it is pure Kotlin. */
    @JvmField
    val frameworkPackages: List<String> =
        listOf(
            "io.ktor",
            "java.sql",
            "javax.sql",
            "com.mongodb",
            "com.zaxxer",
            "org.flywaydb",
            "io.lettuce",
            "com.rabbitmq",
        )

    /**
     * (technology, package prefix): driver APIs that only adapters in `..infrastructure..` may import (hexagonal
     * architecture: use cases, routes and the domain talk to ports, never to a driver).
     */
    @JvmField
    val adapterOnlyPackages: List<Arguments> =
        listOf(
            arguments("JDBC", "java.sql"),
            arguments("JDBC", "javax.sql"),
            arguments("JDBC driver", "org.postgresql"),
            arguments("Hikari", "com.zaxxer"),
            arguments("MongoDB", "com.mongodb"),
            arguments("Redis", "io.lettuce"),
            arguments("RabbitMQ", "com.rabbitmq"),
            arguments("Ktor client", "io.ktor.client"),
        )

    /**
     * JDBC packages the transactional outbox may use directly: its rows are written in the caller's transaction
     * (CONTRACTS section 8), so it works on the same java.sql.Connection as the use case's Unit of Work.
     */
    @JvmField
    val outboxJdbcPackages: Set<String> = setOf("java.sql", "javax.sql")

    /** Prefixes naming the technology behind a repository adapter (CONTRACTS section 14), e.g. Postgres. */
    @JvmField
    val repositoryTechnologyPrefixes: List<String> = listOf("Postgres", "Mongo", "Caching")

    /** JUnit 5 annotations that make a function a test. Not @TestTemplate: Pact's provider verification uses it. */
    @JvmField
    val testAnnotations: List<String> = listOf("Test", "ParameterizedTest", "RepeatedTest", "TestFactory")
}
