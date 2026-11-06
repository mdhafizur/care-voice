#!/usr/bin/env bash
# Restore drill for the PostgreSQL backups (ADR-0026): proves that point-in-time recovery works, end to end.
#   1. take a fresh base backup of carevoice-pg (Barman Cloud plugin → MinIO)
#   2. write a row that must survive, note the time, then write a row that must NOT survive (the "bad write")
#   3. make sure the WAL with both rows is archived
#   4. restore into a scratch cluster at the noted time, from object storage only
#   5. check: the first row is there, the second is not
# The live cluster is never touched except for the two marker rows (removed at the end). Exit code 1 if the check fails.
# KEEP=1 keeps the scratch cluster for inspection. bash 3.2 compatible (macOS).
set -euo pipefail
cd "$(dirname "$0")/.."

NS=${K8S_NAMESPACE:-carevoice}
SOURCE=carevoice-pg
DRILL=carevoice-pg-drill
OBJECT_STORE=carevoice-pg-backups
PLUGIN=barman-cloud.cloudnative-pg.io
KEEP=${KEEP:-0}

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mDRILL FAILED: %s\033[0m\n' "$*"; exit 1; }
sql() { # pod, statement → unaligned, tuples only
  kubectl -n "$NS" exec "$1" -c postgres -- psql -d carevoice -v ON_ERROR_STOP=1 -tAc "$2"
}
wait_for() { # description, timeout seconds, command...: poll every 5 s until the command succeeds
  local what=$1 timeout=$2; shift 2
  local waited=0
  until "$@"; do
    [ "$waited" -lt "$timeout" ] || fail "timed out after ${timeout}s waiting for: $what"
    sleep 5; waited=$((waited + 5))
  done
}

kubectl -n "$NS" get cluster.postgresql.cnpg.io/"$SOURCE" >/dev/null 2>&1 || fail "cluster $SOURCE not found (make k8s-up)"
primary=$(kubectl -n "$NS" get cluster.postgresql.cnpg.io/"$SOURCE" -o jsonpath='{.status.currentPrimary}')
image=$(kubectl -n "$NS" get cluster.postgresql.cnpg.io/"$SOURCE" -o jsonpath='{.spec.imageName}')
run="drill-$(date +%Y%m%d%H%M%S)"
echo "source: $SOURCE (primary $primary), run: $run"

step "1/5 Fresh base backup"
kubectl -n "$NS" apply -f - <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata:
  name: $run
spec:
  cluster:
    name: $SOURCE
  method: plugin
  pluginConfiguration:
    name: $PLUGIN
EOF
backup_done() {
  local phase
  phase=$(kubectl -n "$NS" get backup.postgresql.cnpg.io/"$run" -o jsonpath='{.status.phase}' 2>/dev/null || true)
  [ "$phase" = "failed" ] && fail "backup $run failed: $(kubectl -n "$NS" get backup.postgresql.cnpg.io/"$run" -o jsonpath='{.status.error}')"
  [ "$phase" = "completed" ]
}
wait_for "backup $run to complete" 900 backup_done
echo "backup $run completed"

step "2/5 Marker rows: one before the recovery target, one after it"
sql "$primary" "CREATE TABLE IF NOT EXISTS restore_drill (id bigserial PRIMARY KEY, run text NOT NULL, kind text NOT NULL,
  written_at timestamptz NOT NULL DEFAULT now())" >/dev/null
sql "$primary" "INSERT INTO restore_drill (run, kind) VALUES ('$run', 'before')" >/dev/null
sleep 2
# The database's own clock, so the target lines up exactly with the commit timestamps in the WAL.
target=$(sql "$primary" "SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '+00'")
sleep 2
sql "$primary" "INSERT INTO restore_drill (run, kind) VALUES ('$run', 'after')" >/dev/null
echo "recovery target: $target (the 'after' row was written 2 s later)"

step "3/5 Archive the WAL that contains both rows"
segment=$(sql "$primary" "SELECT pg_walfile_name(pg_switch_wal())")
archived() { [ "$(sql "$primary" "SELECT coalesce(last_archived_wal >= '$segment', false) FROM pg_stat_archiver")" = "t" ]; }
wait_for "WAL segment $segment to be archived" 300 archived
echo "segment $segment is in object storage"

step "4/5 Restore into the scratch cluster $DRILL"
kubectl -n "$NS" delete cluster.postgresql.cnpg.io/"$DRILL" --ignore-not-found --wait >/dev/null
kubectl -n "$NS" apply -f - <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: $DRILL
spec:
  instances: 1
  imageName: $image
  storage:
    size: 1Gi
  bootstrap:
    recovery:
      source: origin
      recoveryTarget:
        targetTime: "$target"
  externalClusters:
    - name: origin
      plugin:
        name: $PLUGIN
        parameters:
          barmanObjectName: $OBJECT_STORE
          serverName: $SOURCE
EOF
for i in $(seq 1 60); do kubectl -n "$NS" get cluster.postgresql.cnpg.io/"$DRILL" >/dev/null 2>&1 && break; sleep 2; done
kubectl -n "$NS" wait --for=condition=Ready cluster.postgresql.cnpg.io/"$DRILL" --timeout=1200s \
  || fail "the scratch cluster did not become ready (kubectl -n $NS describe cluster $DRILL)"
restored=$(kubectl -n "$NS" get cluster.postgresql.cnpg.io/"$DRILL" -o jsonpath='{.status.currentPrimary}')

step "5/5 Verify"
before=$(sql "$restored" "SELECT count(*) FROM restore_drill WHERE run = '$run' AND kind = 'before'")
after=$(sql "$restored" "SELECT count(*) FROM restore_drill WHERE run = '$run' AND kind = 'after'")
observations=$(sql "$restored" "SELECT count(*) FROM observations")
echo "restored: before=$before (expected 1), after=$after (expected 0), observations=$observations"

sql "$primary" "DELETE FROM restore_drill WHERE run = '$run'" >/dev/null
if [ "$KEEP" = "1" ]; then
  echo "kept $DRILL: kubectl -n $NS exec -it $restored -c postgres -- psql -d carevoice"
else
  kubectl -n "$NS" delete cluster.postgresql.cnpg.io/"$DRILL" --wait=false >/dev/null
fi

[ "$before" = "1" ] && [ "$after" = "0" ] || fail "point-in-time recovery returned the wrong data"
printf '\n\033[1;32mDRILL PASSED: restored to %s from object storage, the later write is absent.\033[0m\n' "$target"
