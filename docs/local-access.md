# Local access: URLs and credentials

This page covers the **local docker compose stack**. The local Kubernetes cluster has its own section at the end.
All ports are bound to `127.0.0.1`, so nothing is reachable from other machines.

The credentials are the **development defaults from `.env.example`**. Your own `.env` always wins: if you changed a
value there, use that one. Never reuse these values outside a laptop.

## Two ways to reach a service

- **Direct URL:** each service publishes its own port, for example `http://localhost:8080`. This always works.
- **Host-name URL:** Traefik (the reverse proxy in the stack) routes names like `api.carevoice.127.0.0.1.nip.io` to
  the right container. Traefik listens on `CAREVOICE_HTTP_PORT`, which is `80` by default. If port 80 is taken on
  your machine, set `CAREVOICE_HTTP_PORT=8000` in `.env`. Then **every host-name URL needs `:8000`**, for example
  `http://api.carevoice.127.0.0.1.nip.io:8000/docs`. Without the port, the browser or Postman goes to port 80, where
  something else answers (often "socket hang up").

`*.127.0.0.1.nip.io` is public DNS that always answers `127.0.0.1`, so it needs internet. If you are offline, or your
router blocks such answers (FRITZ!Box DNS rebind protection), set `CAREVOICE_DOMAIN=carevoice.localhost` in `.env`.

Traefik only sends a request to a container that reports healthy. A host-name URL that returns 404 right after a
start usually means the service is still starting: check with `make ps`.

## Services and tools

The host-name URLs below assume the default port 80. Add `:<CAREVOICE_HTTP_PORT>` if you changed it.

| What | Host-name URL | Direct URL | Login | Started by |
|---|---|---|---|---|
| documentation-service API | http://api.carevoice.127.0.0.1.nip.io | http://localhost:8080 | JWT, see demo users | `make up-fast` |
| documentation-service Swagger UI | http://api.carevoice.127.0.0.1.nip.io/docs | http://localhost:8080/docs | none | `make up-fast` |
| alert-service API (only `/v1/alerts`, docs and health by host name) | http://alerts.carevoice.127.0.0.1.nip.io | http://localhost:8081 | JWT (`lead.clara`) | `make up-fast` |
| alert-service Swagger UI | http://alerts.carevoice.127.0.0.1.nip.io/docs | http://localhost:8081/docs | none | `make up-fast` |
| Grafana (dashboards, Explore: Loki, Tempo, Prometheus; logs of every container, infrastructure via Alloy: `{source="docker", service_name="postgres"}`; Postgres logs queries > 500 ms, and every write with `POSTGRES_LOG_STATEMENT=mod`) | http://grafana.carevoice.127.0.0.1.nip.io | http://localhost:3000 | anonymous access; admin `admin` / `admin` | `make up-fast` |
| RabbitMQ management | http://rabbitmq.carevoice.127.0.0.1.nip.io | http://localhost:15672 | `admin` / `admin` (`RABBITMQ_USER` / `RABBITMQ_PASSWORD`) | `make up-fast` |
| Traefik dashboard (routers, services, middlewares) | http://traefik.carevoice.127.0.0.1.nip.io/dashboard/ | — | none (local only) | `make up-fast` |
| Uptime Kuma admin | http://status.carevoice.127.0.0.1.nip.io | http://localhost:3001 | `admin` / `admin` (`KUMA_USERNAME` / `KUMA_PASSWORD`) | `make monitoring-up` |
| Uptime Kuma status page | http://status.carevoice.127.0.0.1.nip.io/status/carevoice | http://localhost:3001/status/carevoice | public | `make monitoring-up` |
| pgAdmin | http://pgadmin.carevoice.127.0.0.1.nip.io | http://localhost:5050 | no pgAdmin login; the database password is below | `make admin-up` |
| mongo-express | http://mongo.carevoice.127.0.0.1.nip.io | http://localhost:8082 | `admin` / `admin` (`MONGO_EXPRESS_PASSWORD`) | `make admin-up` |
| **Homepage** (one start page that links everything above, with live status) | http://home.carevoice.127.0.0.1.nip.io | http://localhost:3002 | none | `make tools-up` |

Health, metrics and the API description need no login: `/livez`, `/readyz`, `/metrics` and `/openapi.yaml` on both
services, on the direct URLs. alert-service's event intake (`POST /v1/events/observations`) is never published: only
documentation-service calls it, inside the compose network.

## Demo users (API)

All demo users have the password `DEMO_PASSWORD` (default `admin123`; the app needs at least 8 characters). Get a
token with `POST /v1/auth/token` and the body `{"username": "...", "password": "..."}`.

| Username | Role | Ward | Can |
|---|---|---|---|
| `nurse.anna` | NURSE | ward-a "Maple Ward" | document, read and confirm observations in ward-a |
| `nurse.ben` | NURSE | ward-b "Oak Ward" | the same for ward-b (use him to show 404s across wards) |
| `lead.clara` | WARD_LEAD | ward-a | everything a nurse can, plus the alerts API |

The tenant is `sunrise-care`. Residents `res-1` to `res-3` live in ward-a, `res-4` to `res-6` in ward-b.

## Databases (direct connections from your machine)

| Database | Connection | Credentials |
|---|---|---|
| PostgreSQL (documentation-service) | `localhost:5432`, database `carevoice` | `admin` / `admin` (`POSTGRES_USER` / `POSTGRES_PASSWORD`) |
| MongoDB (alert-service), replica set `rs0` | `mongodb://localhost:27017/?replicaSet=rs0&directConnection=true`, database `carevoice_alerts` | no auth (local only) |
| Redis | `redis://localhost:6379` | no auth (local only) |
| RabbitMQ (AMQP) | `amqp://localhost:5672`, vhost `carevoice` | `admin` / `admin` |

Shortcuts: `make db-shell` (psql), `make mongo-shell` (mongosh), `make redis-cli`.

In pgAdmin and the database shells, resident names and transcript text look like `v1:...`. That is AES-GCM
ciphertext: the encryption of stored data works as designed.

## Tools

| Tool | How |
|---|---|
| Postman | Import `postman/CareVoice.postman_collection.json`. Set `demoPassword` (default `admin123`) and run folder `01 Auth` first. `docBaseUrl` and `alertBaseUrl` default to the direct URLs (`http://localhost:8080`, `http://localhost:8081`); for host-name URLs, include the port if `CAREVOICE_HTTP_PORT` is not 80. Folder `07 Observation scenarios` (28 requests with checks: spoken formats, review, rejection, AI fallback, one scenario per alert rule) works best right after `make reset-demo` |
| Load test | `make load-smoke` (Gatling; reports in `load-test/build/reports`) |
| Security scan | `make security-scan` (ZAP, Trivy, gitleaks; reports in `build/security`) |
| Protocol guidance (RAG) | `GET /v1/alerts/{alertId}/guidance` as `lead.clara`; Postman folder `05`. Optional keys in `.env`: `GUIDANCE_AI_ENABLED=true` + `ANTHROPIC_API_KEY`, `GUIDANCE_EMBEDDER=voyage` + `VOYAGE_API_KEY` |
| Dead-letter replay | `make dlq-replay` (`LIMIT=n` for at most n) moves events that alert-service gave up on back to its queue, with a fresh set of retries |
| Postman against Kubernetes | Set `docBaseUrl` to `http://carevoice.127.0.0.1.nip.io:9080` and `alertBaseUrl` to `http://localhost:18081` (with the alert-service port-forward running, see below) |

## Resetting the demo data

`make reset-demo` removes everything the demo produced and seeds the demo tenant, wards, residents and users again.
It takes about 30 seconds. It stops both services, empties the Postgres tables, deletes the alerts and processed
events in MongoDB, purges the alert queues (main, retry and dead-letter) and the `carevoice:*` Redis keys, and starts
the services again; they seed at startup. The schema, indexes, migration history and protocol corpus stay.
`FORCE=1 make reset-demo` skips the question. Run it before every demo, so that step 8 finds exactly one new alert.

## Resetting credentials

- Uptime Kuma password: `docker compose exec -it uptime-kuma npm run reset-password`, then update `KUMA_PASSWORD`
  in `.env`. For a new username: `docker compose --profile monitoring rm -sf uptime-kuma`,
  `docker volume rm carevoice_uptime-kuma-v1-data`, set `KUMA_USERNAME` / `KUMA_PASSWORD`, then `make monitoring-up`.
- Demo users: change `DEMO_PASSWORD` in `.env`. The seeder never overwrites existing users, so reset the database
  too: `make down`, `docker volume rm carevoice_postgres-data`, `make up-fast`.
- Postgres and RabbitMQ read their user and password only when their volume is first created. To change them later,
  reset the volume the same way (`carevoice_postgres-data`, `carevoice_rabbitmq-data`). pgAdmin keeps its saved
  server entry in `carevoice_pgadmin-data`; reset that volume too after changing the Postgres user.

## Debugging the containers with IntelliJ

1. `make up-debug`: the normal stack, but one replica per service and a debug port: `localhost:5005`
   (documentation-service) and `localhost:5006` (alert-service). The request deadline is raised to 120 s, so a request
   is not cancelled while you step through it.
2. In IntelliJ, pick **Debug documentation-service (Docker)**, **Debug alert-service (Docker)** or **Debug both services
   (Docker)** in the run configuration list (they come from `.run/` in the repo) and press Debug.
3. Set a breakpoint, for example in `SubmitTranscript.execute`, and send the request from Postman.

Right-click each breakpoint and choose **Suspend: Thread**. With **All**, the whole JVM stops, `/readyz` no longer
answers, and Traefik takes the container out of rotation until you resume. The code in IntelliJ must match what runs
in the container: `make up-debug` rebuilds the images from your current code first. Back to normal: `make up-fast`.

## AI providers (optional)

All AI features are off by default, and the stack works without any key. To try them, put the key in `.env` and
restart with `make up-fast`. Never type a key on the command line: it ends up in the shell history.

| Feature | Variables |
|---|---|
| Extraction fallback (documentation-service) | `AI_FALLBACK_ENABLED=true`, `AI_PROVIDER=claude` + `ANTHROPIC_API_KEY`, or `AI_PROVIDER=gemini` + `GEMINI_API_KEY` |
| Guidance writer (alert-service) | `GUIDANCE_AI_ENABLED=true`, same `AI_PROVIDER` and key |
| Semantic retrieval (alert-service) | `GUIDANCE_EMBEDDER=gemini` + `GEMINI_API_KEY`, or `voyage` + `VOYAGE_API_KEY` |

To test a Gemini key before you turn the features on (the key is read from `.env`):

```bash
set -a; . ./.env; set +a
curl -s "https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL:-gemini-flash-latest}:generateContent" \
  -H "x-goog-api-key: $GEMINI_API_KEY" -H 'Content-Type: application/json' \
  -d '{"contents":[{"parts":[{"text":"pulse 72, bp 140 over 90"}]}],
       "generationConfig":{"responseMimeType":"application/json","thinkingConfig":{"thinkingLevel":"low"}}}'
```

You can see which provider answered in three places: the logs (`provider` field of "AI extraction finished"), the
guidance response (`generatedBy: GEMINI`) and Grafana (`carevoice_ai_fallback_total`).

When the fallback returns nothing, the same log line says why. `result` is `extracted`, `empty` (the model found no
stated value), `malformed`, `timeout` (raise `AI_TIMEOUT_MS`), `error` or `circuit_open`. For `error`, the fields
`httpStatus` and `apiError` hold the provider's answer, for example `429 RESOURCE_EXHAUSTED` (quota),
`400 INVALID_ARGUMENT` (model or request option not supported) or `403 PERMISSION_DENIED` (key). The error message
itself is never logged, because it may repeat the transcript.

```bash
docker compose logs documentation-service --since 30m | grep "AI extraction"
```

`503 UNAVAILABLE` ("high demand") means Google has no capacity right now; it is not a bug here. Switch
`GEMINI_MODEL` to a lighter model (`gemini-3.5-flash-lite` answers in about 1.5 s). To list the models your key may
call:

```bash
KEY=$(sed -n 's/^GEMINI_API_KEY=//p' .env | tail -1)
curl -sS "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200" -H "x-goog-api-key: $KEY" \
  | jq -r '.models[] | select(.supportedGenerationMethods | index("generateContent")) | .name'
unset KEY
```

In zsh, write `${m}:generateContent`, not `$m:generateContent`. zsh reads `:g...` after a variable as a modifier.

## Local Kubernetes (k3d)

`make k8s-up` creates the cluster `carevoice` (1 server and 2 agents) and deploys everything. `make k8s-status` shows
its state, and `make k8s-down` deletes it. The cluster uses ports 9080 (http) and 9443 (https), so it does not clash
with docker compose. Both stacks together need a lot of memory, though: stop compose first (`make down`) unless
Docker Desktop has 12 GB or more.

Note the host name: in Kubernetes the API is `carevoice.127.0.0.1.nip.io`, **without** the `api.` prefix that
compose uses.

| What | URL / command | Login |
|---|---|---|
| documentation-service API + Swagger UI | http://carevoice.127.0.0.1.nip.io:9080 (`/docs`); https on `:9443` (self-signed certificate, so turn off certificate checks in Postman) | JWT, demo users as above |
| Grafana | http://grafana.carevoice.127.0.0.1.nip.io:9080 (row "Backups (Kubernetes)" shows WAL archiving and the backup bucket) | anonymous; admin `admin` / `admin` |
| MinIO console (Postgres backups: base backups and WAL in bucket `carevoice-pg-backups`) | http://minio.carevoice.127.0.0.1.nip.io:9080 | `carevoice-backup` / `kubectl -n carevoice get secret carevoice-backup-s3 -o jsonpath='{.data.ACCESS_SECRET_KEY}' \| base64 -d` |
| alert-service (only inside the cluster) | `kubectl -n carevoice port-forward svc/alert-service 18081:8081`, then http://localhost:18081 | JWT (`lead.clara`) |
| PostgreSQL (CloudNativePG) | `kubectl -n carevoice port-forward svc/carevoice-pg-rw 15432:5432` | `carevoice` / `POSTGRES_PASSWORD` from `.env` |
| MongoDB rs0 | `kubectl -n carevoice exec -it carevoice-mongodb-0 -- mongosh` | no auth (local) |
| RabbitMQ management | `kubectl -n carevoice port-forward svc/carevoice-rabbitmq 15673:15672`, then http://localhost:15673 | `kubectl -n carevoice get secret carevoice-rabbitmq-default-user -o jsonpath='{.data.username}' \| base64 -d` (the same for `password`) |
| Restore drill | `make k8s-pg-restore-drill` (`KEEP=1` keeps the scratch cluster) | — |
