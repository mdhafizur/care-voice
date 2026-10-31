#!/usr/bin/env bash
# Local Kubernetes (k3d) for CareVoice: cluster, operators, databases, observability, images, Helm release.
# Idempotent: run it again after a change and it converges (make k8s-up). Needs docker, k3d, kubectl, helm, .env.
# bash 3.2 compatible (macOS).
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=${K8S_CLUSTER:-carevoice}
NS=${K8S_NAMESPACE:-carevoice}
TAG=${K8S_TAG:-local}
CERT_MANAGER_VER=${CERT_MANAGER_VER:-v1.21.2}
CNPG_VER=${CNPG_VER:-1.30.1}
RABBITMQ_OP_VER=${RABBITMQ_OP_VER:-v2.23.0}
HELM_TIMEOUT=${HELM_TIMEOUT:-10m}

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
for tool in docker k3d kubectl helm; do command -v "$tool" >/dev/null || { echo "missing: $tool (brew install $tool)"; exit 1; }; done
[ -f .env ] || { echo ".env missing: cp .env.example .env"; exit 1; }
set -a; . ./.env; set +a

step "Image caches (pull-through registries for docker.io, ghcr.io, quay.io)"
# Separate containers with their own volumes: they outlive the cluster, so recreating it does not download anything
# again. Remove them with `make k8s-cache-clean`.
ensure_cache() { # name, host port, upstream registry
  local container="k3d-$1"
  if docker container inspect "$container" >/dev/null 2>&1; then
    [ "$(docker container inspect -f '{{.State.Running}}' "$container")" = "true" ] || docker start "$container" >/dev/null
    echo "$container: running"
  else
    k3d registry create "$1" --port "127.0.0.1:$2" --proxy-remote-url "$3" --volume "$1-data:/var/lib/registry" --no-help
  fi
}
ensure_cache carevoice-cache-dockerio 5100 https://registry-1.docker.io
ensure_cache carevoice-cache-ghcr 5101 https://ghcr.io
ensure_cache carevoice-cache-quay 5102 https://quay.io

step "Cluster $CLUSTER (k3d: 1 server + 2 agents)"
if k3d cluster list "$CLUSTER" >/dev/null 2>&1; then
  # The mirror configuration is written into the nodes at creation; an older cluster pulls from the internet directly.
  if ! docker exec "k3d-$CLUSTER-server-0" test -f /etc/rancher/k3s/registries.yaml 2>/dev/null; then
    echo "note: this cluster was created before the image caches; 'make k8s-down && make k8s-up' to use them"
  fi
else
  # An interrupted create or delete can leave node containers behind that k3d no longer lists as a cluster; the next
  # create then fails with "container name ... is already in use". Remove only this cluster's node containers, never
  # the image caches (k3d-$CLUSTER-cache-*), so nothing has to be downloaded again.
  stale=$(docker ps -a --format '{{.Names}}' | grep -E "^k3d-$CLUSTER-((server|agent)-[0-9]+|serverlb|tools)$" || true)
  if [ -n "$stale" ]; then
    echo "removing leftover node containers from an earlier run: $(echo $stale)"
    docker rm -f $stale >/dev/null
    docker volume rm "k3d-$CLUSTER-images" >/dev/null 2>&1 || true
  fi
  k3d cluster create --config deploy/k8s/k3d-cluster.yaml
fi
kubectl config use-context "k3d-$CLUSTER" >/dev/null
kubectl get nodes

# Opt-in (K8S_PRELOAD=1): pull images once on this machine and copy them into the nodes instead of every node pulling
# them itself. Off by default: with Docker Desktop's containerd image store, multi-platform images often cannot be
# exported completely ("content digest ... not found"), and the nodes then pull them anyway.
if [ "${K8S_PRELOAD:-0}" = "1" ]; then
  step "Pre-loading images into the cluster nodes"
  PRELOAD_IMAGES="mongo:8.0 redis:7.4-alpine grafana/otel-lgtm:0.37.0
    ghcr.io/cloudnative-pg/postgresql:18.6-system-trixie ghcr.io/cloudnative-pg/pgbouncer:1.25.2"
  for image in $PRELOAD_IMAGES; do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull --platform "linux/$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/')" "$image"
  done
  # shellcheck disable=SC2086 # word splitting intended: one argument per image
  k3d image import -c "$CLUSTER" $PRELOAD_IMAGES || echo "pre-load failed; the nodes will pull the images themselves"
fi

step "Operators: cert-manager $CERT_MANAGER_VER, CloudNativePG $CNPG_VER, RabbitMQ $RABBITMQ_OP_VER"
# Order matters: the RabbitMQ operator manifest contains cert-manager resources (its webhook certificate), and
# cert-manager's webhook must answer before anything cert-manager-related can be created.
# Waits until a Deployment is Available. Not `kubectl rollout status`: that gives up as soon as the Deployment passes its
# 10-minute progress deadline, which a first image pull over a slow connection easily does, although the pull goes on
# and the Deployment recovers by itself. Pulls go through the local caches, so this long wait only happens once.
wait_ready() { # namespace, deployment
  echo "waiting for deployment/$2 (first run: images download through the cache, can take a while)"
  kubectl -n "$1" wait --for=condition=Available "deploy/$2" --timeout=1800s
}

retry() { # attempts, command...: re-run until it succeeds (webhooks need a few seconds after their pods are ready)
  local n=$1; shift
  for i in $(seq 1 "$n"); do "$@" && return 0; echo "  not ready yet, retrying ($i/$n)..."; sleep 10; done
  "$@"
}
kubectl apply -f "https://github.com/cert-manager/cert-manager/releases/download/$CERT_MANAGER_VER/cert-manager.yaml" >/dev/null
wait_ready cert-manager cert-manager
wait_ready cert-manager cert-manager-cainjector
wait_ready cert-manager cert-manager-webhook
retry 30 kubectl apply -f deploy/k8s/cert-manager/

kubectl apply --server-side -f "https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-${CNPG_VER%.*}/releases/cnpg-$CNPG_VER.yaml" >/dev/null
retry 12 kubectl apply -f "https://github.com/rabbitmq/cluster-operator/releases/download/$RABBITMQ_OP_VER/cluster-operator.yml" >/dev/null
wait_ready cnpg-system cnpg-controller-manager
wait_ready rabbitmq-system rabbitmq-cluster-operator

step "Namespace $NS and secrets from .env"
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create secret generic carevoice-secrets \
  --from-literal=JWT_SECRET="$JWT_SECRET" \
  --from-literal=DATA_ENCRYPTION_KEY="$DATA_ENCRYPTION_KEY" \
  --from-literal=DEMO_PASSWORD="$DEMO_PASSWORD" \
  --from-literal=ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-}" \
  --from-literal=GEMINI_API_KEY="${GEMINI_API_KEY:-}" \
  --from-literal=VOYAGE_API_KEY="${VOYAGE_API_KEY:-}" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create secret generic carevoice-pg-app --type=kubernetes.io/basic-auth \
  --from-literal=username=carevoice --from-literal=password="$POSTGRES_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

step "Databases: PostgreSQL (2 instances + PgBouncer), MongoDB rs0 (3), Redis, RabbitMQ (3 nodes)"
kubectl -n "$NS" apply -f deploy/k8s/databases/
echo "waiting for PostgreSQL (first run pulls images, can take a few minutes)..."
for i in $(seq 1 60); do kubectl -n "$NS" get cluster.postgresql.cnpg.io/carevoice-pg >/dev/null 2>&1 && break; sleep 2; done
kubectl -n "$NS" wait --for=condition=Ready cluster.postgresql.cnpg.io/carevoice-pg --timeout=1800s
for i in $(seq 1 60); do kubectl -n "$NS" get deploy/carevoice-pg-pooler-rw >/dev/null 2>&1 && break; sleep 2; done
wait_ready "$NS" carevoice-pg-pooler-rw
echo "waiting for MongoDB rs0..."
kubectl -n "$NS" wait --for=condition=complete job/carevoice-mongodb-rs-init --timeout=1800s
wait_ready "$NS" carevoice-redis
echo "waiting for RabbitMQ..."
for i in $(seq 1 60); do kubectl -n "$NS" get secret carevoice-rabbitmq-default-user >/dev/null 2>&1 && break; sleep 5; done
kubectl -n "$NS" wait --for=condition=AllReplicasReady rabbitmqcluster/carevoice-rabbitmq --timeout=1800s

step "Observability: Grafana otel-lgtm in namespace observability"
kubectl create namespace observability --dry-run=client -o yaml | kubectl apply -f -
cm() { kubectl -n observability create configmap "$@" --dry-run=client -o yaml | kubectl apply --server-side --force-conflicts -f - >/dev/null; }
cm grafana-dashboards --from-file=deploy/grafana/dashboards/
cm grafana-dashboard-provider --from-file=carevoice.yaml=deploy/grafana/provisioning/dashboards/carevoice.yaml
cm grafana-datasources --from-file=grafana-datasources.yaml=deploy/grafana/provisioning/datasources/grafana-datasources.yaml
cm prometheus-config --from-file=prometheus.yaml=deploy/k8s/observability/prometheus.yaml
cm otel-collector-config --from-file=otelcol-config.yaml=deploy/otel-collector/otelcol-config.yaml
kubectl -n observability apply -f deploy/k8s/observability/otel-lgtm.yaml
kubectl -n observability rollout restart deploy/otel-lgtm >/dev/null   # pick up ConfigMap changes
kubectl -n "$NS" apply -f deploy/k8s/observability/scrape-targets.yaml

step "Images: build on this machine (make images-fast) and import into k3d"
make images-fast
k3d image import -c "$CLUSTER" "carevoice/documentation-service:$TAG" "carevoice/alert-service:$TAG"

step "Helm release carevoice (migrations run first as pre-install/pre-upgrade Jobs)"
helm upgrade --install carevoice deploy/helm/carevoice -n "$NS" \
  -f deploy/k8s/values-k3d.yaml --set image.tag="$TAG" --wait --timeout "$HELM_TIMEOUT"
# Same tag every build: restart so the pods run the image just imported.
kubectl -n "$NS" rollout restart deploy/documentation-service deploy/alert-service >/dev/null
kubectl -n "$NS" rollout status deploy/documentation-service --timeout=300s
kubectl -n "$NS" rollout status deploy/alert-service --timeout=300s

step "Done"
cat <<EOF
API:      http://carevoice.127.0.0.1.nip.io:9080        (https on :9443, self-signed)
Swagger:  http://carevoice.127.0.0.1.nip.io:9080/docs
Grafana:  http://grafana.carevoice.127.0.0.1.nip.io:9080
Status:   make k8s-status
alert-service is cluster-internal; reach it with: kubectl -n $NS port-forward svc/alert-service 18081:8081
EOF
