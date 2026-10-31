#!/usr/bin/env bash
# Index benchmark against the running compose stack (docs/indexing.md). Writes build/db-bench/report.md.
#
#   make db-bench                    # 1M observations, 300k alerts
#   ROWS=200000 ALERTS=50000 make db-bench
#
# Works in throwaway schemas/databases (PostgreSQL schema carevoice_bench, MongoDB database carevoice_bench) that are
# dropped afterwards; application data is only read (table and index definitions), never written.
set -euo pipefail

COMPOSE="${COMPOSE:-docker compose}"
ROWS="${ROWS:-1000000}"
ALERTS="${ALERTS:-300000}"
OUT=build/db-bench
mkdir -p "$OUT"

for service in postgres mongo; do
  if [ -z "$($COMPOSE ps --status running -q "$service" 2>/dev/null)" ]; then
    echo "$service is not running: start the stack first (make up-fast)" >&2
    exit 1
  fi
done

echo "==> PostgreSQL: seeding $ROWS observations and measuring (about a minute)"
# shellcheck disable=SC2016  # $POSTGRES_USER / $POSTGRES_DB are expanded inside the container
$COMPOSE exec -T postgres sh -c 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v rows="$1" -v src=public -f -' \
  sh "$ROWS" < scripts/db-bench/postgres.sql | sed '/./,$!d' > "$OUT/postgres.md"

echo "==> MongoDB: seeding $ALERTS alerts and measuring"
$COMPOSE exec -T mongo mongosh --quiet \
  --eval "$(printf 'const ALERTS = %s;\n' "$ALERTS"; cat scripts/db-bench/mongo.js)" > "$OUT/mongo.md"

{
  cat "$OUT/postgres.md"
  echo
  cat "$OUT/mongo.md"
} > "$OUT/report.md"

cat "$OUT/report.md"
echo
echo "Report: $OUT/report.md"
