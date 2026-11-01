-- Time limits for every session of the application role.
--
-- Cancelling a request cannot stop a blocking JDBC call, so the database itself bounds the work:
--   statement_timeout = 5s: a slow or blocked statement (a lock wait, a missing index) is cancelled server-side with
--     SQLState 57014, which the service answers with 503 + Retry-After instead of holding a request and a pooled
--     connection indefinitely. Covers lock waits too, since they count towards the statement's time.
--   idle_in_transaction_session_timeout = 15s: a session that opened a transaction and then stalled (a frozen pod, a
--     lost client) is terminated, so its row locks do not block everyone else.
--
-- Set on the role, not per connection: behind PgBouncer in transaction mode a per-connection SET is lost or leaks to
-- another client, while role defaults apply to every server connection PgBouncer opens as this role. They take effect
-- for new sessions; pooled connections pick them up as they are recycled (Hikari maxLifetime: 30 minutes).
--
-- A future migration that legitimately runs longer (an index on a large table) starts with:
--   SET LOCAL statement_timeout = 0;
-- SET LOCAL only lasts for the migration's own transaction.
ALTER ROLE CURRENT_USER SET statement_timeout = '5s';
ALTER ROLE CURRENT_USER SET idle_in_transaction_session_timeout = '15s';
