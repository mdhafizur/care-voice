# 10. HTTP API conventions: RFC 9457 errors, precise status codes, the QUERY method

- Status: Accepted
- Date: 2026-10-25

## Context

Clients (the mobile app, other services, load tests) need predictable, machine-readable behaviour. The status code
must tell a client whether retrying makes sense.

## Decision

- **Errors follow RFC 9457**: `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`,
  `requestId`, and an `errors` list for validation problems. 5xx bodies never contain exception messages or stack
  traces.
- **Status codes carry meaning**:
  - 400 malformed request or wrong type, 422 well-formed but invalid values, 415 wrong content type, 413 body too
    large, 409 conflict, 428 missing `If-Match`, 412 outdated `If-Match`.
  - 429 (rate limit) and 503 (a dependency is down, a database statement took too long, or the request ran past its
    deadline `REQUEST_TIMEOUT_MS`) always carry `Retry-After`.
  - Any 4xx except 429 means "do not retry the same request". 429, 503 and other 5xx may be retried. The outbox relay
    relies on exactly this split.
- **Order of checks**: authenticated (401), then role (403), then exists in the caller's ward (404), then business
  rules. A resource in another ward is **404, not 403**, so its existence is not revealed.
- **Versioning in the URL** (`/v1`). Breaking changes get `/v2`.
- **`QUERY /v1/observations`** for complex filters. QUERY is a new HTTP method: safe and idempotent like GET, but
  with a body. `POST /v1/observations/search` does the same for clients and proxies that do not support QUERY yet.
- **Contract-first OpenAPI 3.1**: the spec is changed first. It is served at `/openapi.yaml` with Swagger UI at
  `/docs`, and every integration test checks the responses against it.

## Consequences

- Clients can build retries generically from the status code.
- Every new status code a handler can return must be added to the spec, or the contract tests fail.
- QUERY is not supported everywhere yet, hence the POST twin.

## Alternatives considered

- **Filters in GET query strings**: length limits and awkward arrays, and filters may contain resident ids that
  should not end up in access logs.
- **POST for search only**: works, but POST is by definition neither safe nor idempotent, so caches and retries
  cannot assume either.
