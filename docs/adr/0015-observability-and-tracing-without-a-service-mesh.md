# 15. Observability: OpenTelemetry agent, Grafana LGTM, tracing without a service mesh

- Status: Accepted
- Date: 2026-10-25

## Context

A request passes through documentation-service, Postgres, the outbox, RabbitMQ, alert-service and MongoDB, partly
asynchronously. When something is slow or lost, we must be able to follow one request through all of it.

## Decision

- **Correlation**: every request gets an `X-Request-Id`. It is taken from the caller when valid, and generated
  otherwise. It is returned in the response and put in the log context (MDC) together with `userId`, `tenantId` and
  `wardId` (set after authentication). `MDCContext` carries the MDC along when a coroutine switches threads.
- **Tracing without a sidecar**: the OpenTelemetry Java agent instruments Ktor, JDBC, Mongo, Lettuce and the
  RabbitMQ client, and passes on the W3C `traceparent` header. For the asynchronous part, the outbox row stores the
  original `request_id` and `traceparent`, and the relay sends them as HTTP or AMQP headers. So the trace continues
  from the nurse's HTTP request into alert-service.
- **Logs**: JSON to stdout with `traceId` and `spanId`; one access-log line per request; no sensitive fields.
  Errors also go to stderr.
- **Metrics**: Micrometer in Prometheus format, with **server-side percentile histograms** (SLO buckets 100 ms,
  300 ms, 1 s), so p50/p90/p95/p99 can be combined across pods. **Exemplars** link a slow latency bucket to the exact
  trace in Tempo. Business metrics: transcripts, observations by source and status, AI fallback, outbox lag, alerts,
  AMQP and consumer pauses. In Kubernetes, the CloudNativePG and MinIO exporters add backup metrics (ADR-0026).
- **Grafana otel-lgtm** (Loki, Grafana, Tempo, Prometheus) with a provisioned dashboard. Uptime Kuma adds external
  black-box checks (ADR-0020). In docker compose, Grafana Alloy also ships the logs of every infrastructure container
  (Postgres, MongoDB, RabbitMQ, Redis, Traefik) to Loki.
- **Export interval = scrape interval (15 s)**: with the agent's default of 60 s, `rate()` over Grafana's ~1 minute
  `$__rate_interval` saw only one sample, and latency panels showed "No data" despite traffic. Found during the demo
  and fixed with `OTEL_METRIC_EXPORT_INTERVAL=15000` (set in both Dockerfiles).
- **Log context on exported logs**: `OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES=*`
  turns `requestId`, `userId` and `wardId` into Loki structured metadata. One request is then found with
  `{service_name=~".+"} | requestId="<id>"`, across both services and the asynchronous outbox relay.
- **Structured fields**: log values are SLF4J key-value pairs in both services, and the agent forwards them
  (`..._CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES=true`). Loki can then filter on `result`, `durationMs`, `dependency` and
  similar fields. Without that setting, only the MDC keys reached Loki.
- **Health and replicas on the dashboard**: replicas running (scrape targets `up`), ready versus not ready (the most
  frequent `/readyz` status per replica in the last minute, each replica counted once), liveness, and probe rates
  per replica. Probe and `/metrics` requests are dropped from traces before Tempo, so they do not hide real traffic.

## Consequences

- One click from a p99 spike to the trace, and from the trace to its logs.
- No mesh to install or operate. The agent adds some startup time and memory.
- Passing context across the asynchronous gap is our job (outbox columns, message headers), and it is tested.

## Alternatives considered

- **Istio / Envoy service mesh**: gives mTLS, retries and traffic metrics. But traces still need the application to
  forward headers, and a mesh cannot see inside the outbox or the broker. Too much to operate for two services.
- **Client-side percentiles**: cannot be combined across pods (an average of p99s is not a p99).
