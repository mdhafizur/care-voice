#!/usr/bin/env bash
# Purges all demo data and seeds it fresh, keeping containers, volumes, schema and indexes (make reset-demo).
#
# Order matters:
#   1. stop both services, so the outbox relay and the event consumer cannot write while data is removed;
#   2. Postgres: TRUNCATE every application table (schema, migrations history, indexes and statistics stay);
#   3. MongoDB: delete alerts and processed events (indexes, migration history and the protocol corpus stay);
#   4. RabbitMQ: purge the alert queues (main, retry, dead letter);
#   5. Redis: delete the carevoice:* keys (rate limits; pub/sub keeps no data);
#   6. start both services: documentation-service seeds the demo tenant, wards, residents and users at startup
#      (DEMO_SEED_ENABLED=true), and the in-memory caches start empty.
#
# FORCE=1 skips the confirmation. For a full reset including volumes and passwords, see docs/local-access.md.
set -euo pipefail

COMPOSE="${COMPOSE:-docker compose}"
env_value() { sed -n "s/^$1=//p" .env 2>/dev/null | tail -1 | tr -d "'\""; }
MONGO_DB="${MONGO_DATABASE:-$(env_value MONGO_DATABASE)}"
MONGO_DB="${MONGO_DB:-carevoice_alerts}"
VHOST="${RABBITMQ_VHOST:-$(env_value RABBITMQ_VHOST)}"
VHOST="${VHOST:-carevoice}"
DOC="${DOC:-http://localhost:8080}"

if [ "$(env_value DEMO_SEED_ENABLED)" != "true" ]; then
  echo "DEMO_SEED_ENABLED is not true in .env: the data would be purged but not seeded again." >&2
  exit 1
fi
for service in postgres mongo rabbitmq redis; do
  if [ -z "$($COMPOSE ps --status running -q "$service" 2>/dev/null)" ]; then
    echo "$service is not running: start the stack first (make up-fast)" >&2
    exit 1
  fi
done
if [ "${FORCE:-0}" != 1 ]; then
  printf 'This deletes all transcripts, observations, alerts, users and residents of the local stack. Continue? [y/N] '
  read -r answer
  [ "$answer" = y ] || [ "$answer" = Y ] || { echo "Nothing changed."; exit 0; }
fi

step() { printf '\n==> %s\n' "$*"; }

step "Stopping documentation-service and alert-service"
$COMPOSE stop documentation-service alert-service

step "PostgreSQL: emptying the application tables"
# shellcheck disable=SC2016  # expanded inside the container
$COMPOSE exec -T postgres sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
TRUNCATE audit_log, outbox, observations, transcripts, users, residents, wards, tenants RESTART IDENTITY CASCADE;
DROP SCHEMA IF EXISTS carevoice_bench CASCADE;
SELECT 'tables emptied: ' || count(*) FROM tenants;
SQL

step "MongoDB: deleting alerts and processed events"
$COMPOSE exec -T mongo mongosh --quiet "$MONGO_DB" --eval '
  const a = db.alerts.deleteMany({}).deletedCount;
  const p = db.processed_events.deleteMany({}).deletedCount;
  db.getSiblingDB("carevoice_bench").dropDatabase();
  print("alerts deleted: " + a + ", processed events deleted: " + p);'

step "RabbitMQ: purging the alert queues"
for queue in alert-service.observations alert-service.observations.retry alert-service.observations.dlq; do
  $COMPOSE exec -T rabbitmq rabbitmqctl -q purge_queue -p "$VHOST" "$queue" 2>/dev/null &&
    echo "purged $queue" || echo "skipped $queue (not declared yet)"
done

step "Redis: deleting carevoice:* keys"
$COMPOSE exec -T redis sh -c \
  'n=0; for k in $(redis-cli --scan --pattern "carevoice:*"); do redis-cli del "$k" >/dev/null; n=$((n+1)); done; echo "keys deleted: $n"'

step "Starting the services (documentation-service seeds the demo data at startup)"
$COMPOSE start documentation-service alert-service

printf 'Waiting for documentation-service'
for _ in $(seq 1 60); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$DOC/readyz")" = 200 ]; then
    echo " ready."
    break
  fi
  printf '.'
  sleep 2
done

step "Checking the demo logins"
bash scripts/demo.sh check
