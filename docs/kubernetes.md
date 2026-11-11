# Kubernetes in CareVoice

How Kubernetes works, mapped to the files in this repository, and how to run the local cluster.
URLs and credentials: [local-access.md](local-access.md#local-kubernetes-k3d).
Decisions: [ADR-0020](adr/0020-deployment-kubernetes-helm-uptime-kuma.md) (deployment) and
[ADR-0026](adr/0026-postgres-backups-wal-archiving.md) (backups).

## The core idea: desired state and control loops

You describe in YAML **what should be running**, for example "2 replicas of documentation-service, 768 MiB each,
checked on `/readyz`". Kubernetes stores this desired state. Controllers compare it with reality all the time and fix
any difference: they replace a crashed pod, move the pods of a lost node, and roll out a new image pod by pod.
You never say "start this container". You say "this is how it should be".

docker compose *starts* containers once. Kubernetes *keeps them in the desired state*.

## The building blocks

| Concept | What it is | In CareVoice |
|---|---|---|
| Cluster | Control plane (API server, scheduler, controllers, etcd = the stored desired state) plus nodes that run containers | k3d: 1 server + 2 agents in two simulated zones ([k3d-cluster.yaml](../deploy/k8s/k3d-cluster.yaml)) |
| Namespace | A named partition of one cluster: resource names, access rules (RBAC), resource quotas and NetworkPolicies apply per namespace. Services are found as `<service>.<namespace>` | `carevoice` (both services, their databases and MinIO), `observability` (Grafana otel-lgtm), one per operator (`cert-manager`, `cnpg-system` with the Barman Cloud plugin, `rabbitmq-system`), `kube-system` (Traefik, CoreDNS, metrics-server) |
| Pod | Smallest unit: one or more containers sharing an IP. Disposable: replaced, never repaired | each service replica, each database instance |
| Deployment | "Keep N identical pods running" plus rolling updates | both services ([deployment.yaml](../deploy/helm/carevoice/templates/deployment.yaml)); `maxUnavailable: 0`, so a new pod is ready before an old one stops |
| StatefulSet | Like a Deployment, but stable pod names and one disk per pod | MongoDB `carevoice-mongodb-0/1/2` ([mongodb.yaml](../deploy/k8s/databases/mongodb.yaml)) |
| Job | Runs to completion once | migrations (Helm pre-install/pre-upgrade hooks), MongoDB replica-set init, creating the backup bucket |
| Service | Stable name and IP in front of changing pods; load-balances to **ready** pods only | `alert-service:8081`, `carevoice-pg-pooler-rw:5432`, `carevoice-rabbitmq:5672` |
| Ingress | HTTP routing from outside to Services, by host name | Traefik (built into k3s): `carevoice.127.0.0.1.nip.io`, `grafana.carevoice.127.0.0.1.nip.io`, `minio.carevoice.127.0.0.1.nip.io` (backup console) |
| ConfigMap / Secret | Configuration and credentials, injected as environment variables or files | `documentation-service-config`; `carevoice-secrets` created from `.env` by `make k8s-up` |
| Probes | **startup**: wait for JVM start; **liveness** failing → container restarted; **readiness** failing → removed from the Service, *not* restarted | `/livez` and `/readyz` ([ADR-0017](adr/0017-health-endpoints-livez-readyz.md)) |
| HorizontalPodAutoscaler | Adds/removes pods by CPU | 2–6 replicas per service ([hpa.yaml](../deploy/helm/carevoice/templates/hpa.yaml)) |
| PodDisruptionBudget | Limits voluntary disruptions (node drain, upgrade) | at least 1 pod per service stays up ([pdb.yaml](../deploy/helm/carevoice/templates/pdb.yaml)) |
| Topology spread | Spreads replicas across nodes and zones | services and MongoDB; the two k3d zones make it visible |
| NetworkPolicy | Firewall between pods | default deny, then only the needed flows ([networkpolicy.yaml](../deploy/helm/carevoice/templates/networkpolicy.yaml)) |
| Operator | A controller that adds new resource kinds and runs complex software | CloudNativePG (`Cluster`, `Pooler` = PgBouncer, `Backup`, `ScheduledBackup`) with its Barman Cloud plugin (`ObjectStore`), RabbitMQ Cluster Operator (`RabbitmqCluster`), cert-manager (`ClusterIssuer`, certificates) |
| Helm | Template tool: `values.yaml` + templates → the YAML above, installed as one release with hooks | [deploy/helm/carevoice](../deploy/helm/carevoice), local overrides in [values-k3d.yaml](../deploy/k8s/values-k3d.yaml) |

## Nodes: one server, two agents

k3d runs k3s, a small Kubernetes distribution. In k3s a **server** is a control-plane node and an **agent** is a
worker. In k3d every node is a Docker container on your machine that acts like a separate machine.

- **Server** (`k3d-carevoice-server-0`): the API server (`kubectl`, `helm` talk to it), the stored desired state, the
  scheduler (which pod runs on which node) and the controllers (replace crashed pods, run HPAs). In k3s it also runs
  ordinary pods.
- **Agents** (`k3d-carevoice-agent-0`, `-1`): the kubelet starts containers, runs the probes and reports back.

There are three nodes in two zones, so the high-availability settings actually do something: the three MongoDB
members, the three RabbitMQ nodes and the service replicas land on different nodes
(`kubectl -n carevoice get pods -o wide`). Production has the same shape with real machines: usually 3 servers (the
control plane survives losing one) and many agents across real availability zones.

Docker Desktop's built-in Kubernetes could also run the chart (one node, no ingress controller included). k3d was
chosen because the cluster is defined in a file, has several nodes, and is easy to throw away.

## What `make k8s-up` does

[scripts/k8s-up.sh](../scripts/k8s-up.sh). It is idempotent: run it again after a change or a failure, and it brings
the cluster to the same state.

1. **Image caches and cluster**: start three pull-through registries (`docker.io`, `ghcr.io`, `quay.io` on
   127.0.0.1:5100-5102, each with its own volume), then create the k3d cluster from `deploy/k8s/k3d-cluster.yaml`
   (skipped when it exists) with those caches as mirrors. Every image is downloaded from the internet once; the
   nodes, and every later cluster, pull it from this machine. The caches survive `make k8s-down`;
   `make k8s-cache-clean` removes them.
2. **Operators**: cert-manager first (the RabbitMQ operator needs its webhook), then the self-signed
   `ClusterIssuer`, then CloudNativePG and the RabbitMQ Cluster Operator, and last the Barman Cloud plugin for
   PostgreSQL backups (it needs both cert-manager and CloudNativePG).
3. **Namespace and secrets**: `carevoice`, plus `carevoice-secrets` and `carevoice-pg-app` from `.env`. The backup
   storage secret `carevoice-backup-s3` is generated once and then kept, because MinIO and the stored backups depend
   on it.
4. **Databases** (`deploy/k8s/databases/`): first MinIO and its bucket `carevoice-pg-backups` (the bucket must exist
   before PostgreSQL archives anything), then PostgreSQL primary + replica with PgBouncer and WAL archiving, MongoDB
   `rs0` (3 members), Redis and RabbitMQ (3 nodes). The script waits until each one is ready.
5. **Observability**: Grafana otel-lgtm in namespace `observability`, with the same dashboards as compose.
   Prometheus finds every replica through headless Services, scrapes PostgreSQL and MinIO for the backup panels, and
   evaluates the alert rules in `deploy/k8s/observability/prometheus-rules.yaml`.
6. **Images**: built on the host (`make images-fast`) and imported into the cluster (`k3d image import`).
   Optional `K8S_PRELOAD=1`: also pulls the database and Grafana images once on the host and imports them, instead
   of every node pulling them (works when the image store can export them; see Troubleshooting).
7. **Helm release**: migration Jobs run first; only if they succeed are the Deployments, Services, Ingress, HPAs,
   PDBs and NetworkPolicies applied. `--wait` returns when the pods are ready.

## Helm: how the application is packaged

Helm is the package manager and template tool for Kubernetes. You write templates once, fill them with values per
environment, and install the result as one versioned unit, called a **release**. In one sentence: Kubernetes runs
YAML; Helm turns one chart with parameters into the right YAML per environment, installs it as a versioned release
with the migrations first, and can roll it back with one command.

| Piece | In this repo |
|---|---|
| Chart | [deploy/helm/carevoice](../deploy/helm/carevoice): `Chart.yaml` (name, version) and `templates/` (Deployment, Service, Ingress, HPA, PDB, NetworkPolicy, ConfigMap, migration Job) |
| Default values | [values.yaml](../deploy/helm/carevoice/values.yaml): replicas, resources, probes, configuration, NetworkPolicy peers |
| Environment overrides | [values-k3d.yaml](../deploy/k8s/values-k3d.yaml) (laptop: single Redis, MongoDB without auth), [values-production.yaml](../deploy/helm/carevoice/values-production.yaml) (real domain, Let's Encrypt) |
| Release | `carevoice` in namespace `carevoice`, created by `helm upgrade --install` in `make k8s-up` |

What it does for CareVoice:

1. **Templating**: one Deployment template is rendered once per entry of `services` (documentation, alert), so probe,
   security and rollout settings are defined once for both services.
2. **Ordering with hooks**: the migration Jobs are annotated `helm.sh/hook: pre-install,pre-upgrade`. Helm runs
   Flyway and the Mongo migrations before it changes any Deployment; if a migration fails, the upgrade stops and the
   running pods stay on the old version (a failed Job is kept for `kubectl logs`).
3. **One unit, waited for**: `--wait` reports success only when every pod is ready.
4. **History and rollback**: every upgrade is a numbered revision with its chart version and values.
5. **Clean removal**: `helm uninstall` removes exactly what the release created. The databases are not part of the
   chart (they live in `deploy/k8s/databases`), so uninstalling the application never deletes data.

```bash
helm template carevoice deploy/helm/carevoice -f deploy/k8s/values-k3d.yaml   # render the YAML, apply nothing
helm -n carevoice status carevoice                                            # state of the release
helm -n carevoice get values carevoice                                        # values the live release uses
helm -n carevoice history carevoice                                           # every revision and its result
helm -n carevoice rollback carevoice <revision>                               # back to an earlier revision
helm -n carevoice uninstall carevoice                                         # remove the application, keep the data
```

## A request through the cluster

```
browser ──> k3d load balancer :9080 ──> Traefik (Ingress) ──> Service documentation-service ──> ready pod
documentation-service pod ──> carevoice-pg-pooler-rw (PgBouncer) ──> carevoice-pg-rw (primary)
                          ──> carevoice-redis, carevoice-rabbitmq ──> alert-service pods ──> carevoice-mongodb-*
every pod ──> otel-lgtm.observability:4318 (traces, metrics, logs: the Service otel-lgtm in namespace observability)
```

## Things to try in a demo

```bash
make k8s-status                                              # everything at a glance
kubectl -n carevoice delete pod -l app.kubernetes.io/name=documentation-service --wait=false
kubectl -n carevoice get pods -w                             # self-healing: replacements appear at once
kubectl -n carevoice exec -it carevoice-mongodb-0 -- mongosh --eval 'rs.status().members.map(m => m.stateStr)'
kubectl -n carevoice get cluster.postgresql.cnpg.io carevoice-pg   # primary + replica, failover by the operator
kubectl -n carevoice rollout restart deploy/alert-service    # rolling update, no downtime (maxUnavailable 0)
kubectl -n carevoice get hpa -w                              # watch the HPA during `make load-smoke`

docker stop k3d-carevoice-agent-1                            # "a machine dies"
kubectl get nodes                                            # agent-1 NotReady after ~40 s
kubectl -n carevoice get pods -o wide -w                     # its pods are recreated on the other nodes
docker start k3d-carevoice-agent-1

make k8s-pg-restore-drill                                    # backups: point-in-time restore into a scratch cluster
kubectl -n carevoice get backups.postgresql.cnpg.io          # base backups (nightly + the drill's)
PRIMARY=$(kubectl -n carevoice get cluster.postgresql.cnpg.io carevoice-pg -o jsonpath='{.status.currentPrimary}')
kubectl -n carevoice exec "$PRIMARY" -c postgres -- psql -tAc 'SELECT * FROM pg_stat_archiver'   # WAL archiving
```

Losing one node never takes a service down: the PDBs keep at least one pod per service during drains, MongoDB keeps
a primary with 2 of 3 members, and RabbitMQ quorum queues keep a majority.

## Backups and point-in-time recovery

PostgreSQL writes every change to its write-ahead log (WAL) first. Through CloudNativePG's Barman Cloud plugin it
uploads every finished WAL file to MinIO, and it takes a full base backup every night (ADR-0026). With both, the
database can be restored to any second of the last 14 days.

Restoring means creating a **new** cluster from the bucket with a `recoveryTarget`. The damaged cluster stays
untouched until the restored copy is checked. `make k8s-pg-restore-drill` does exactly that: it writes a row, notes
the time, writes a second row, restores to the noted time, and checks that only the first row is there. A backup
that has never been restored is a hope, not a backup.

Watch it in Grafana (row "Backups (Kubernetes)") and in the MinIO console (bucket `carevoice-pg-backups`). Two alert
rules cover it: `PostgresWalArchivingFailing` and `PostgresWalArchiveBacklog`.

## Troubleshooting

| Symptom | Look at |
|---|---|
| A step of `make k8s-up` fails | run it again (idempotent); webhooks and image pulls are often just slow the first time |
| First run waits very long ("waiting for PostgreSQL") | image pulls (MongoDB took ~20 min per node on a slow line before the caches). `kubectl -n carevoice get events --sort-by=.lastTimestamp \| tail` shows `Pulling`; wait or rerun. With the caches only the very first run downloads |
| "this cluster was created before the image caches" | the mirror configuration is written into the nodes at creation: `make k8s-down && make k8s-up` once |
| Keep the cluster between sessions | `k3d cluster stop carevoice` / `k3d cluster start carevoice` keeps data and images; `make k8s-down` deletes the cluster (the caches stay) |
| `k3d image import`: "content digest ... not found" | Docker Desktop's containerd image store cannot export some multi-platform images; harmless, the nodes pull them. Pre-loading is opt-in (`K8S_PRELOAD=1`) for this reason |
| `failed calling webhook "webhook.cert-manager.io"` | cert-manager's webhook was not answering yet; the script now waits for it and retries |
| Pod `Pending` | `kubectl -n carevoice describe pod <pod>`: usually not enough memory (Docker Desktop: give it 8–10 GB, stop compose) |
| Pod `CrashLoopBackOff` | `kubectl -n carevoice logs <pod> --previous` |
| Pod running but not ready | its `/readyz` names the dependency: `kubectl -n carevoice port-forward <pod> 18080:8080`, `curl localhost:18080/readyz`; often a NetworkPolicy |
| Migration Job failed | `kubectl -n carevoice logs job/documentation-service-migrate` (failed Jobs are kept for this) |
| `UPGRADE FAILED: pre-upgrade hooks failed` | a migration Job could not be created or failed; nothing else was changed. Fix the cause and run `make k8s-up` again (`helm -n carevoice history carevoice` shows the failed revision) |
| Start over | `make k8s-down && make k8s-up` |
