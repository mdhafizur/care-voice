# 21. Slim multi-stage container images with a jlink runtime

- Status: Accepted
- Date: 2026-10-25

## Context

Every scale-out and every deploy pulls the images, so their size and contents affect startup time and attack
surface. The JVM must also respect the container's memory limit.

## Decision

One four-stage Dockerfile per service; the build context is the repository root:
1. **build**: `eclipse-temurin:21-jdk`, the dependency layer first, a BuildKit Gradle cache, then `installDist`.
2. **jlink**: a minimal Java runtime, built from a fixed list of modules (`JRE_MODULES`). The first plan was to detect
   the modules with `jdeps`. That fails on modular jars that require automatic modules
   (kotlinx-coroutines-reactive → reactive-streams), and it cannot see modules that are only loaded through
   ServiceLoader (logback → java.xml, the JDBC driver). A missing module shows up as `NoClassDefFoundError` at
   startup, and is then added to the list.
3. **otel-agent**: the OpenTelemetry Java agent, downloaded and verified by checksum.
4. **runtime**: `debian:bookworm-slim`, a non-root user (uid 10001), only CA certificates, an exec-form `ENTRYPOINT`.

The application files come from one of two stages, chosen with the build argument `APP_SOURCE`:
- `app-from-gradle` (default): compiled inside Docker. Reproducible, and what CI uses.
- `app-from-host` (`make up-fast`): copies `build/install/<service>`, built on the host with the local Gradle cache.
  Seconds, instead of a first build of about ten minutes inside Docker.

The runtime image is the same either way.

The JVM flags fit containers: `-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError`. JFR can be
switched on through `JAVA_TOOL_OPTIONS`.

## Consequences

- No JDK, compiler or build tools in the runtime image, which is much smaller than a full JDK image.
- An OutOfMemoryError ends the process (and Kubernetes restarts the pod) instead of leaving a half-working JVM.
- A missing JDK module shows up only at runtime. Starting the image in CI catches it.

## Alternatives considered

- **Distroless Java**: smaller still, but no shell for debugging during a demo.
- **Alpine (musl)**: a smaller base, with occasional native-library and DNS surprises for the JVM.
- **GraalVM native image**: fast startup, but reflection configuration for Ktor, the drivers and the OTel agent is
  real work, and the OTel Java agent cannot be used with it.
