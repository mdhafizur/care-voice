# 16. PgBouncer in Kubernetes only; no external pooler for MongoDB

- Status: Accepted
- Date: 2026-10-25

## Context

Each pod has its own HikariCP connection pool. With autoscaling (up to 6 pods per service, plus migration Jobs),
pods × pool size can grow beyond what PostgreSQL handles well: every Postgres connection is a separate server process
with a real memory cost. MongoDB works differently: its connections are cheap threads on the server, and the driver
already pools connections and routes them to the replica-set primary.

## Decision

- **PostgreSQL in Kubernetes**: a CloudNativePG `Pooler` (PgBouncer, `poolMode: transaction`) sits in front of the
  primary. The services connect through it, and the Hikari pool in each pod stays small.
- **Migrations bypass PgBouncer** (`DATABASE_MIGRATION_URL`): Flyway holds a session-level advisory lock, which
  transaction pooling breaks.
- The code stays compatible with transaction mode:
  - only transaction-scoped advisory locks;
  - no session state (`SET`, `LISTEN/NOTIFY`, temp tables);
  - server-side prepared statements can be switched off (`DATABASE_PREPARE_THRESHOLD`);
  - time limits are set on the database role, not per session: `statement_timeout = 5s` and
    `idle_in_transaction_session_timeout = 15s` (migration V4). A `SET` per connection would not survive PgBouncer.
- **docker compose connects directly**: two pods do not need a pooler, and fewer moving parts make local debugging
  easier.
- **MongoDB**: no pooler. The driver's own pool is configured (max/min size, idle time, wait-queue timeout) and knows
  the replica set. The server-selection timeout is short, so a failover becomes 503 + `Retry-After` instead of a
  hanging request.

## Consequences

- Postgres connections stay bounded, however far the services scale.
- A rule for the code: anything session-scoped is a bug behind PgBouncer.

## Alternatives considered

- **A bigger `max_connections`**: the memory cost per connection makes this the expensive option.
- **pgcat / Odyssey**: comparable poolers; PgBouncer comes built into CloudNativePG.
- **mongos or a proxy for MongoDB**: only useful for sharding, which this data does not need.
