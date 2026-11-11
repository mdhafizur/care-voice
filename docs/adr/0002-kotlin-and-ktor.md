# 2. Kotlin and Ktor, without a DI framework

- Status: Accepted
- Date: 2026-10-25

## Context

The services are small and spend most of their time waiting on I/O: the database, the message broker, HTTP calls and
the AI API. They must be easy to explain from end to end in a short demo, and Kotlin on the JVM is the target platform.

## Decision

- **Kotlin** on the JVM, compiled to Java 21 bytecode, shipped with a small jlink runtime (ADR-0021).
- **Ktor 3** as the HTTP server and the HTTP client. Its plugins (request ids, authentication, error pages, rate
  limiting, metrics, server-sent events, the request deadline) are installed one by one in code. Ktor is built on
  coroutines, so waiting, cancelling and backpressure are visible in the code instead of hidden in a framework.
- **No dependency injection (DI) framework.** `Application.kt` builds every object by passing its dependencies to
  the constructor. The whole object graph is readable in one file, and a test replaces a port by passing a different
  constructor argument.
- **Plain JDBC with HikariCP instead of an ORM.** The SQL that matters (`SKIP LOCKED`, row-value comparisons for
  cursors, partial indexes) is written and reviewed as SQL.

## Consequences

- Fast startup and a small image. There is no classpath scanning or reflection magic to explain.
- More wiring code by hand than Spring Boot needs, and nothing comes for free (metrics, health checks): each is a few
  lines, written once.

## Alternatives considered

- **Spring Boot (WebFlux or MVC)**: more features out of the box, but much more to explain, and the annotations hide
  the request pipeline that the demo wants to show.
- **Exposed, jOOQ or Hibernate**: useful at scale. For a handful of queries, plain SQL is clearer.
