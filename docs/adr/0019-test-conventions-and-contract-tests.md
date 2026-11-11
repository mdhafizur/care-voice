# 19. Test conventions, contract-first API, consumer-driven contracts

- Status: Accepted
- Date: 2026-10-25

## Context

Reviewers who did not write a test must be able to read it. And the tests must catch the mistakes that matter for an
API: a missing status code, a leaked field, a broken contract between two services.

## Decision

- **TDD** for the domain and the use cases; the domain has about 400 fast unit tests.
- **One test class per endpoint**, with fixed `@Nested` groups in this order:
  1. "General Request Structure" (pagination);
  2. "Request Validation" (every non-2xx answer, the 5xx smoke test last);
  3. "Response Validation" (happy path, the full body checked with `assertJsonEquals`);
  4. "Business Logic" (side effects; every successful PATCH is read back).
- Authentication and authorization baselines are reusable JUnit test interfaces: missing, expired or malformed
  token, wrong issuer, wrong role → 403, other ward → 404.
- **Data-driven negative tests**: `@ParameterizedTest` over shared sets of invalid values, never hand-picked values or
  loops inside one test. Every test name starts with "should".
- Every response in an integration test is checked against the OpenAPI spec (`OpenApiContract`).
- **Pact**: documentation-service is the consumer of the HTTP endpoint and of the AMQP message, and alert-service
  verifies both as the provider.
- **Deterministic**: injected `Clock` and id generators, no `Thread.sleep`, polling helpers with a time limit.
- Quality gates in `./gradlew build`: Spotless/ktlint, Konsist architecture rules, Kover coverage (80 % of lines and
  65 % of branches by default, with a ratchet per service while tests are being completed).
- Load tests with Gatling (smoke, load, stress) against the running stack.
- A retrieval evaluation set for the protocol guidance (ADR-0023): in every build, every alert rule must retrieve its
  own protocol, and unrelated text must retrieve none.
- Security scan on demand (`make security-scan`): an OWASP ZAP API scan of both services, driven by their OpenAPI
  specs with real tokens; Trivy for known vulnerabilities in the images; gitleaks for committed secrets. Reports go
  to `build/security`.

## Consequences

- The spec, the code and the tests cannot drift apart without a test failing.
- In places there is more test code than production code. That is accepted for an API whose consumers are other
  teams.

## Alternatives considered

- **Only end-to-end tests across both services**: slow and flaky. Pact checks the contract in each build, separately
  for each side.
