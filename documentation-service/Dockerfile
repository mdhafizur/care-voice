# syntax=docker/dockerfile:1
#
# documentation-service image. Build context = repository root:
#   docker build -f documentation-service/Dockerfile -t carevoice/documentation-service .
# (docker compose does this for you, see docker-compose.yml.)
#
# Stages: build (Gradle installDist) -> jre (jlink a minimal runtime) -> otel-agent (pinned, checksum-verified
# download) -> runtime (debian slim + JRE + agent + app, non-root).

ARG JDK_IMAGE=eclipse-temurin:21.0.12.1_1-jdk-noble
ARG RUNTIME_IMAGE=debian:bookworm-20261020-slim
ARG OTEL_AGENT_VERSION=2.32.0
ARG OTEL_AGENT_SHA256=f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82
# Where the application comes from:
#   app-from-gradle (default)  compiled inside Docker by the `build` stage: reproducible, needs nothing on the host
#   app-from-host              copied from build/install/ on the host (`make up-fast` runs installDist first): reuses
#                              the host's Gradle cache, so no dependency download or compilation inside Docker.
#                              BuildKit skips the unused `build` stage entirely.
ARG APP_SOURCE=app-from-gradle

# ---------------------------------------------------------------------------------------------------------------------
# 1. Build: compile and package with Gradle (build/install/documentation-service)
# ---------------------------------------------------------------------------------------------------------------------
FROM ${JDK_IMAGE} AS build
WORKDIR /workspace

# Build files only, so the dependency layer below is reused until a build file or the version catalog changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY build-logic/ build-logic/
COPY domain/build.gradle.kts domain/
COPY contracts/build.gradle.kts contracts/
COPY test-support/build.gradle.kts test-support/
COPY documentation-service/build.gradle.kts documentation-service/
COPY alert-service/build.gradle.kts alert-service/
COPY architecture-test/build.gradle.kts architecture-test/
# settings.gradle.kts includes `load-test`; Gradle 9 refuses to configure a project whose directory does not exist.
# An empty directory is enough for the build to configure (the module is not needed for this image).
RUN mkdir -p load-test

# Dependency layer: with only build files present, installDist downloads the Gradle distribution, compiles the
# build-logic convention plugins and downloads every runtime jar of the service (the module jars it produces are empty
# and are deleted again). `gradle dependencies` alone would resolve metadata only, not the jars.
# The cache mount keeps ~/.gradle between builds; `locked` because both service images may build in parallel and
# Gradle's cross-process lock handling does not work across containers.
RUN --mount=type=cache,id=carevoice-gradle,target=/root/.gradle,sharing=locked \
    ./gradlew :documentation-service:installDist --no-daemon -x test \
      -Pkotlin.compiler.execution.strategy=in-process \
    && rm -rf domain/build contracts/build documentation-service/build

# Sources of the modules this service needs (domain, contracts and the service itself).
COPY domain/src/ domain/src/
COPY contracts/src/ contracts/src/
COPY documentation-service/src/ documentation-service/src/

# in-process: no separate Kotlin daemon in a throw-away build container.
RUN --mount=type=cache,id=carevoice-gradle,target=/root/.gradle,sharing=locked \
    ./gradlew :documentation-service:installDist --no-daemon -x test \
      -Pkotlin.compiler.execution.strategy=in-process

# ---------------------------------------------------------------------------------------------------------------------
# 1b. Application files, from one of two sources (selected with the APP_SOURCE build argument, see the top)
# ---------------------------------------------------------------------------------------------------------------------
FROM scratch AS app-from-gradle
COPY --from=build /workspace/documentation-service/build/install/documentation-service /app

FROM scratch AS app-from-host
COPY documentation-service/build/install/documentation-service /app

FROM ${APP_SOURCE} AS app

# ---------------------------------------------------------------------------------------------------------------------
# 2. JRE: only the JDK modules the application needs (fixed list below)
# ---------------------------------------------------------------------------------------------------------------------
FROM ${JDK_IMAGE} AS jre
# Modules in the list that are easy to forget:
#   java.instrument  -javaagent (OpenTelemetry Java agent)
#   jdk.unsupported  sun.misc.Unsafe, used by Netty, Caffeine and the Kotlin coroutines debug probes
#   jdk.crypto.ec    elliptic-curve TLS cipher suites (HTTPS to the Claude API, TLS to managed databases)
#   jdk.management   com.sun.management MXBeans used by the agent's and Micrometer's JVM metrics
#   jdk.jfr          Java Flight Recorder, available on demand via JAVA_TOOL_OPTIONS; also used by the agent's
#                    runtime-telemetry
#   jdk.naming.dns   DNS lookups through JNDI (mongodb+srv / SRV records), loaded reflectively
#   jdk.jdwp.agent   the Java debug agent (-agentlib:jdwp), only switched on by docker-compose.debug.yml
#   java.logging, java.management  needed by the agent itself
# --compress=zip-6 is the JDK 21 spelling (the numeric --compress=2 is deprecated since 21).
# --strip-debug needs objcopy, which the Temurin JDK image ships (binutils).
# Fixed module list instead of detecting it with jdeps: jdeps fails on modular jars whose dependencies are automatic
# modules (kotlinx-coroutines-reactive requires org.reactivestreams), and it cannot see modules needed only by
# ServiceLoader-loaded libraries (logback -> java.xml, JDBC driver). If a new library needs another module, the app
# fails at startup with NoClassDefFoundError naming the class; add its module here.
ARG JRE_MODULES=java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.security.jgss,java.security.sasl,java.sql,java.xml,jdk.crypto.ec,jdk.jfr,jdk.management,jdk.jdwp.agent,jdk.naming.dns,jdk.net,jdk.unsupported
RUN set -eux; \
    jlink \
      --add-modules "${JRE_MODULES}" \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /jre; \
    /jre/bin/java --list-modules

# ---------------------------------------------------------------------------------------------------------------------
# 3. OpenTelemetry Java agent: pinned version, download fails the build when the SHA-256 does not match
# ---------------------------------------------------------------------------------------------------------------------
FROM scratch AS otel-agent
ARG OTEL_AGENT_VERSION
ARG OTEL_AGENT_SHA256
# Maven Central publishes the .sha256 next to the jar; the value above was taken from it.
ADD --chmod=0644 --checksum=sha256:${OTEL_AGENT_SHA256} \
    https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar \
    /opentelemetry-javaagent.jar

# ---------------------------------------------------------------------------------------------------------------------
# 4. Runtime
# ---------------------------------------------------------------------------------------------------------------------
FROM ${RUNTIME_IMAGE} AS runtime

# ca-certificates only: trust store for OS-level TLS. No curl/wget/shell tools beyond what slim already has.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 carevoice \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home --shell /usr/sbin/nologin carevoice

ENV JAVA_HOME=/opt/java \
    PATH=/opt/java/bin:$PATH \
    LANG=C.UTF-8

# Least frequently changing layers first: JRE, then agent, then the application.
COPY --from=jre /jre /opt/java
COPY --from=otel-agent /opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
# Files stay owned by root: the non-root user can read and execute the app but not modify it.
COPY --from=app /app /app

# JVM flags, read by every JVM in the container (including `migrate` runs):
#   -javaagent:...                 OpenTelemetry Java agent: traces, metrics and logs over OTLP, W3C traceparent
#                                  propagation (CONTRACTS section 10)
#   -XX:MaxRAMPercentage=75        heap sized from the container memory limit, not the host's RAM; 25 % stays free
#                                  for metaspace, thread stacks, Netty direct buffers and the agent
#   -XX:InitialRAMPercentage=50    start with half of the limit committed to avoid repeated heap growth (and the GC
#                                  pauses that come with it) during warm-up
#   -XX:+UseG1GC                   explicit, because the JVM silently picks SerialGC when it sees < 2 CPUs or
#                                  < 1792 MB, which is common for containers; G1 keeps pauses short for a web service
#   -XX:+ExitOnOutOfMemoryError    exit instead of limping on in an undefined state; the orchestrator restarts the
#                                  container and the failure is visible
# JFR can be added at run time, e.g. JAVA_TOOL_OPTIONS="... -XX:StartFlightRecording=duration=60s,filename=/tmp/rec.jfr"
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar -XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# OpenTelemetry agent configuration (all overridable at run time):
#   OTEL_SERVICE_NAME                  service.name resource attribute (Grafana service variable)
#   OTEL_EXPORTER_OTLP_ENDPOINT        grafana/otel-lgtm in docker compose; OTLP over HTTP (4318) is the agent default
#   OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES
#                                      copy MDC keys (requestId, userId, tenantId, wardId, job) onto the log records the
#                                      agent sends to Loki, where they become searchable structured metadata:
#                                      {service_name=~".+"} | requestId="<id>"
#   OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES
#                                      also copy the structured fields of each log line (SLF4J key-value pairs:
#                                      result, durationMs, dependency, ...) so they are filterable in Loki, not only
#                                      on stdout: {service_name="alert-service"} | result="error"
#   OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_LOG_ATTRIBUTES
#                                      add thread.name and thread.id to every log record, like the `thread` field on
#                                      stdout. Shown as `thread` in Grafana (otelcol-config.yaml renames it). With
#                                      coroutines a request can hop threads, so requestId/traceId identify a request;
#                                      the thread helps with background jobs and pool warnings (Hikari housekeeper)
#   OTEL_METRIC_EXPORT_INTERVAL        push metrics every 15 s (agent default 60 s), matching Prometheus' 15 s scrape
#                                      interval; with 60 s, rate() over Grafana's ~1 min $__rate_interval often has a
#                                      single sample and dashboards show "No data"
#   OTEL_METRICS_EXEMPLAR_FILTER       attach exemplars (trace ids) only to measurements made inside a sampled span,
#                                      which links latency histograms to Tempo traces (CONTRACTS section 14)
#   OTEL_INSTRUMENTATION_COMMON_LOGGING_TRACE_ID / _SPAN_ID
#                                      MDC key names the agent injects into logback; CONTRACTS section 10 requires
#                                      traceId/spanId (the agent default is trace_id/span_id)
ENV OTEL_SERVICE_NAME=documentation-service \
    OTEL_RESOURCE_ATTRIBUTES=service.namespace=carevoice \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-lgtm:4318 \
    OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
    OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES=* \
    OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES=true \
    OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_LOG_ATTRIBUTES=true \
    OTEL_METRIC_EXPORT_INTERVAL=15000 \
    OTEL_METRICS_EXEMPLAR_FILTER=trace_based \
    OTEL_INSTRUMENTATION_COMMON_LOGGING_TRACE_ID=traceId \
    OTEL_INSTRUMENTATION_COMMON_LOGGING_SPAN_ID=spanId

USER 10001:10001
WORKDIR /app
EXPOSE 8080

# Exec form: the Gradle start script ends in `exec java ...`, so the JVM becomes PID 1 and receives SIGTERM directly
# (graceful shutdown). Pass `migrate` as the argument to run the Flyway migrations and exit.
ENTRYPOINT ["/app/bin/documentation-service"]
