-- Correlation of the asynchronous publish with the request that produced the event (docs/CONTRACTS.md, section 14).
-- Expand-only change: both columns are nullable, so code that does not write them (older replicas during a rolling
-- deploy) keeps working.
ALTER TABLE outbox ADD COLUMN request_id TEXT;
ALTER TABLE outbox ADD COLUMN traceparent TEXT;
