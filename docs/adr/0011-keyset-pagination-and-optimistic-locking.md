# 11. Keyset cursor pagination, optimistic locking and conditional requests

- Status: Accepted
- Date: 2026-10-25

## Context

Observation lists keep growing while nurses page through them. Two nurses can confirm the same observation at the
same moment.

## Decision

- **Keyset (cursor) pagination**: the cursor is an opaque base64url string that holds a direction, `recordedAt` and
  `id`. The next page is `WHERE (recorded_at, id) < (?, ?) ORDER BY recorded_at DESC, id DESC LIMIT n+1`; the extra
  row tells whether another page exists. Responses carry an RFC 8288 `Link` header (`first`, `next`, `prev`) and
  `X-Total-Count`. A malformed cursor is a 400.
- **Optimistic locking**: changeable resources have a `version`, sent as the `ETag` header.
  `PATCH .../confirm` requires `If-Match` with that version: missing is 428, outdated is 412. The update runs as
  `... WHERE id = ? AND version = ?`, so it only succeeds if nobody changed the row in between.
- **Conditional GET**: list and detail responses carry an ETag. A matching `If-None-Match` returns 304 without a
  body.

## Consequences

- A page costs the same at any depth (an index seek, no `OFFSET` scan). A row inserted between two page loads
  neither shifts nor repeats items.
- No jumping straight to "page 37"; fine for a list ordered by time.
- No lost updates, and no database locks held while a person thinks.

## Alternatives considered

- **OFFSET/LIMIT**: slow for deep pages, and pages shift when rows are inserted.
- **Pessimistic locking (`SELECT ... FOR UPDATE`)**: holds locks while waiting for a person, and does not work across
  separate HTTP requests anyway.
