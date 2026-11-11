# 20. Deployment: docker compose locally, Kubernetes with Helm, Uptime Kuma

- Status: Accepted
- Date: 2026-10-25

## Context

The system must run with one command on a laptop, for development and demos. It must also deploy to Kubernetes with
the same images and the same configuration model.

## Decision

- **docker compose** (`make up`, or `make up-fast`): all dependencies, 2 replicas of each service behind Traefik,
  one-shot migration containers, Grafana otel-lgtm and Grafana Alloy (logs of the infrastructure containers).
  Optional profiles: `monitoring` (Uptime Kuma), `admin` (pgAdmin, mongo-express) and `tools` (Homepage, one start
  page for every UI).
- **Kubernetes** through one Helm chart (`deploy/helm/carevoice`): Deployments with an HPA (2–6 replicas), a PDB,
  topology spread, probes on `/livez` and `/readyz`, migrations as pre-install/pre-upgrade Jobs, and a Traefik
  ingress with cert-manager TLS. Of the two services, only documentation-service is exposed; alert-service stays
  inside the cluster.
- Databases in Kubernetes run through operators where they exist: CloudNativePG (with PgBouncer, WAL archiving and
  backups to object storage, ADR-0026) and the RabbitMQ cluster operator. MongoDB runs as a replica-set
  StatefulSet. These are plain manifests, not the Bitnami charts the first draft assumed (Bitnami's image
  distribution changed in 2025). The service names stayed the same, so the chart did not change.
- **Local cluster with k3d** (`deploy/k8s`, `scripts/k8s-up.sh`, `make k8s-up`): 1 server + 2 agents in two zones,
  defined as code and easy to throw away, so topology spread and PDBs really do something. The ingress listens on
  127.0.0.1:9080/9443, next to docker compose. Laptop differences live in `values-k3d.yaml`: a single Redis instead
  of Sentinel, MongoDB without auth. Besides the API, the cluster exposes Grafana and the MinIO console (backups) by
  host name. Docker Desktop's built-in Kubernetes could also run the chart (one node, no ingress controller
  included); k3d was chosen for the repeatable multi-node setup.
- Host names are values: `*.127.0.0.1.nip.io` with a self-signed issuer locally; `values-production.yaml` with the
  placeholder `api.carevoice.example` and Let's Encrypt.
- **Uptime Kuma** (compose profile `monitoring`, pinned to 1.23 because its automation client supports 1.23) for
  external black-box monitoring and a status page. An idempotent setup container configures it as code: readiness
  and liveness endpoints, the public API by host name, Grafana, and every datastore.
- 12-factor configuration: everything comes from environment variables and is validated at startup (fail fast, all
  errors at once).

## Consequences

- One image and one configuration model, from laptop to cluster.
- Running stateful systems in Kubernetes is real operational work. In production, managed services (RDS/Cloud SQL,
  Atlas, a managed broker) would usually replace the databases inside the cluster.
- The first `make k8s-up` is mostly image downloads. Local pull-through caches mean each image is downloaded only
  once; `K8S_PRELOAD=1` pulls once on the host and imports the images into the nodes, where the image store allows it.

## Alternatives considered

- **Kustomize**: would work. Helm was chosen for templated values per environment and for migration Jobs as hooks.
- **Managed PaaS** (Cloud Run, App Runner): simpler, but hides exactly the topics the demo should show.
