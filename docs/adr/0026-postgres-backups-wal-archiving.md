# 26. PostgreSQL backups: continuous WAL archiving with point-in-time recovery

- Status: Accepted
- Date: 2026-11-06

## Context

documentation-service keeps what nurses documented in PostgreSQL. In Kubernetes, CloudNativePG runs a primary and a
streaming replica. That covers a lost server or node. It does not cover the cases that a replica copies faithfully: a
bad migration, a wrong `DELETE`, a stolen account, or losing the whole cluster and its disks. Care documentation has
legal retention duties, so "restore the database as it was at 14:32 yesterday" must be possible, and proven.

PostgreSQL writes every change to its write-ahead log (WAL) before it changes the data files. A base backup plus all
WAL written after it can rebuild the database at any moment in between. That is point-in-time recovery (PITR).

## Decision

- **Continuous WAL archiving to object storage** through CloudNativePG's **Barman Cloud plugin**
  (`plugin-barman-cloud`, installed by `make k8s-up`). The older built-in `barmanObjectStore` setting is deprecated
  since CloudNativePG 1.26. Every finished WAL segment is uploaded (gzip, two at a time). `archive_timeout = 5min`
  forces a new segment at least every 5 minutes while anything is written. This bounds the data lost if the whole
  cluster is gone (recovery point objective, RPO ≤ 5 minutes). Losing a single instance loses nothing.
- **Nightly base backup** (`ScheduledBackup`, 02:00, taken from the standby, plus one right after creation).
- **Retention 14 days** (`ObjectStore.spec.retentionPolicy`): any second within the last two weeks can be restored.
- **Object storage**: locally MinIO inside the cluster (`deploy/k8s/databases/minio.yaml`), with its console at
  `minio.carevoice.127.0.0.1.nip.io:9080`. In production: S3, GCS or Azure Blob in another region, with versioning
  and object lock, and credentials that can write but not delete.
- **A restore is a new cluster**, started from the object store with a `recoveryTarget`, never an overwrite in place.
  The damaged cluster stays available for comparison until the restored one is checked.
- **Proven by a drill**: `make k8s-pg-restore-drill` takes a backup, writes a row, notes the time, writes a second
  row, restores into a scratch cluster at the noted time, and fails unless exactly the first row is there.
- **Alerts** (Prometheus rules, `deploy/k8s/observability/prometheus-rules.yaml`): archiving that fails for
  10 minutes (critical), and a backlog of more than 10 segments waiting to be archived (warning; WAL that is not yet
  archived also fills the primary's disk).
- **Dashboard**: the Grafana row "Backups (Kubernetes)" shows the time since the last WAL upload, failed uploads,
  the archive backlog, and the bucket's size and object count (MinIO metrics, public only locally).

## Consequences

- **How much data can be lost (RPO):** at most about 5 minutes of writes if the whole cluster and its disks are gone.
  Nothing is lost when only one instance dies, because the replica has the data.
- **How long a restore takes (RTO):** a new cluster loads the last base backup and replays the WAL after it. For this
  data size that takes minutes; the drill measures it.
- Object storage becomes a dependency of the primary. If archiving fails for a long time, WAL piles up on the
  primary's disk. The backlog alert fires long before the disk is full.
- docker compose has no backups. It is the developer stack, and its single database container can be thrown away.
- MongoDB (alerts) has no backups yet: snapshots or `mongodump` to the same bucket are still open (gap G6).

## Alternatives considered

- **`pg_dump` on a schedule:** only a copy of one moment. Everything written since the last dump is lost, and
  restoring a large database from a dump is slow. Useful as an extra, portable copy, not as the main recovery tool.
- **Volume snapshots (CSI):** fast, but tied to the storage class and the cluster. Without WAL archiving they give
  no point-in-time recovery either.
- **pgBackRest or WAL-G in a sidecar:** mature tools, but they would work around the operator. The Barman Cloud plugin
  is how CloudNativePG itself backs up, restores and reports backup status.
