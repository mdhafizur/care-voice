# CareVoice: one entry point for every Gradle task.
# `make` or `make help` lists the targets. Variables can be overridden per call, e.g.
#   make test MODULE=domain
#   make test MODULE=documentation-service TEST='*SubmitTranscript*'

GRADLE  ?= ./gradlew
ARGS    ?=
MODULE  ?=
TEST    ?=

# ":module:" prefix when MODULE is set, empty otherwise (task runs in every module).
PREFIX := $(if $(MODULE),:$(MODULE):,)
TEST_FILTER := $(if $(TEST),--tests '$(TEST)',)

.DEFAULT_GOAL := help
.PHONY: help build assemble install test test-embedded test-containers test-arch test-fault check \
        format lint coverage coverage-verify pitest pact sbom run-documentation run-alert \
        load-smoke load load-stress deps refresh clean \
        up up-fast up-debug images-fast down logs ps migrate dlq-replay db-shell mongo-shell redis-cli rabbitmq-ui grafana monitoring-up admin-up tools-up observability-reset \
        security-scan db-bench demo demo-check reset-demo k8s-up k8s-down k8s-status k8s-cache-clean k8s-pg-restore-drill

help: ## List all targets
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

## ---- Build -------------------------------------------------------------------

build: ## Compile, test, format check and coverage gate (everything CI runs)
	$(GRADLE) $(PREFIX)build $(ARGS)

assemble: ## Compile and package without running tests
	$(GRADLE) $(PREFIX)assemble $(ARGS)

install: ## Build runnable distributions (build/install/<service>) for both services
	$(GRADLE) :documentation-service:installDist :alert-service:installDist $(ARGS)

## ---- Tests -------------------------------------------------------------------

test: ## Run tests with embedded databases, no Docker needed (MODULE=..., TEST=... to narrow)
	CAREVOICE_TEST_DB=embedded $(GRADLE) $(PREFIX)test $(TEST_FILTER) $(ARGS)

test-embedded: test ## Alias for `test`

test-containers: ## Run tests against Testcontainers (same images as production, needs Docker)
	CAREVOICE_TEST_DB=containers $(GRADLE) $(PREFIX)test $(TEST_FILTER) $(ARGS)

test-arch: ## Architecture rules only (Konsist)
	$(GRADLE) :architecture-test:test $(ARGS)

test-fault: ## Fault-injection tests (Toxiproxy, needs Docker)
	CAREVOICE_TEST_DB=containers $(GRADLE) :documentation-service:test -PincludeTags=fault-injection $(ARGS)

pact: ## Contract tests: consumer writes the pact, provider verifies it
	$(GRADLE) :documentation-service:test --tests 'com.carevoice.documentation.contract.*' $(ARGS)
	$(GRADLE) :alert-service:test --tests '*Pact*' $(ARGS)

pitest: ## Mutation testing on the domain module (report in domain/build/reports/pitest)
	$(GRADLE) :domain:pitest $(ARGS)

## ---- Quality -----------------------------------------------------------------

check: ## All verification tasks (tests, ktlint, coverage gate)
	$(GRADLE) $(PREFIX)check $(ARGS)

format: ## Reformat all Kotlin and Gradle files (ktlint via Spotless)
	$(GRADLE) $(PREFIX)spotlessApply $(ARGS)

lint: ## Check formatting without changing files
	$(GRADLE) $(PREFIX)spotlessCheck $(ARGS)

coverage: ## HTML coverage report per module (build/reports/kover/html)
	$(GRADLE) $(PREFIX)koverHtmlReport $(ARGS)

coverage-verify: ## Fail if coverage is below the minimum (80 % lines, 65 % branches)
	$(GRADLE) $(PREFIX)koverVerify $(ARGS)

sbom: ## CycloneDX SBOM of each service's runtime dependencies (build/reports)
	$(GRADLE) :documentation-service:cyclonedxDirectBom :alert-service:cyclonedxDirectBom $(ARGS)

## ---- Run locally ---------------------------------------------------------------
# The services read their configuration from environment variables (see .env.example).
# These targets export .env if it exists, so `cp .env.example .env` is all the setup needed.

run-documentation: ## Run documentation-service on port 8080 with variables from .env
	@set -a; [ -f .env ] && . ./.env; set +a; $(GRADLE) :documentation-service:run $(ARGS)

run-alert: ## Run alert-service on port 8081 with variables from .env
	@set -a; [ -f .env ] && . ./.env; set +a; $(GRADLE) :alert-service:run $(ARGS)

## ---- Load tests (Gatling) ------------------------------------------------------
# BASE_URL defaults to the local load balancer; override with make load BASE_URL=https://...
# The demo users' password comes from DEMO_PASSWORD in .env. Tune with ARGS, e.g.
#   make load ARGS="-Dusers=200 -DdurationSeconds=300"   make load-stress ARGS="-DmaxUsersPerSec=100"

BASE_URL ?= http://localhost:8080

load-smoke: ## One user through every endpoint
	@set -a; [ -f .env ] && . ./.env; set +a; $(GRADLE) :load-test:gatlingRun --simulation=com.carevoice.loadtest.SmokeSimulation -DbaseUrl=$(BASE_URL) -DdemoPassword="$$DEMO_PASSWORD" $(ARGS)

load: ## "100 nurses documenting at once"
	@set -a; [ -f .env ] && . ./.env; set +a; $(GRADLE) :load-test:gatlingRun --simulation=com.carevoice.loadtest.DocumentationLoadSimulation -DbaseUrl=$(BASE_URL) -DdemoPassword="$$DEMO_PASSWORD" $(ARGS)

load-stress: ## Ramp up until errors appear
	@set -a; [ -f .env ] && . ./.env; set +a; $(GRADLE) :load-test:gatlingRun --simulation=com.carevoice.loadtest.StressSimulation -DbaseUrl=$(BASE_URL) -DdemoPassword="$$DEMO_PASSWORD" $(ARGS)

## ---- Housekeeping --------------------------------------------------------------

deps: ## Runtime dependency tree of a module (MODULE=documentation-service by default)
	$(GRADLE) :$(or $(MODULE),documentation-service):dependencies --configuration runtimeClasspath $(ARGS)

refresh: ## Re-resolve all dependencies (after editing the version catalog)
	$(GRADLE) --refresh-dependencies help $(ARGS)

clean: ## Delete all build outputs
	$(GRADLE) clean $(ARGS)

## ---- Docker Compose --------------------------------------------------------------
# Needs Docker with Compose v2 and a .env file (cp .env.example .env). SERVICE=... narrows `logs`.

COMPOSE ?= docker compose
SERVICE ?=

up: ## Build the images and start the whole stack in the background
	$(COMPOSE) up -d --build

images-fast: ## Build the images from jars compiled on this machine (local Gradle cache, no compile inside Docker)
	$(GRADLE) :documentation-service:installDist :alert-service:installDist -x test $(ARGS)
	APP_SOURCE=app-from-host $(COMPOSE) build

up-fast: images-fast ## Like `up`, but with images-fast: much quicker on a laptop
	$(COMPOSE) up -d

up-debug: images-fast ## Like up-fast, with one replica per service and a debug port (5005 doc, 5006 alert) for IntelliJ
	$(COMPOSE) -f docker-compose.yml -f docker-compose.debug.yml up -d
	@echo "IntelliJ: run 'Debug documentation-service (Docker)' (localhost:5005) or 'Debug alert-service (Docker)' (localhost:5006)"
	@echo "Back to normal (2 replicas, no debug port): make up-fast"

down: ## Stop and remove the stack's containers (volumes are kept)
	$(COMPOSE) --profile monitoring --profile admin down

logs: ## Follow container logs (SERVICE=documentation-service to narrow)
	$(COMPOSE) logs -f --tail=200 $(SERVICE)

ps: ## Show the stack's containers and their health
	$(COMPOSE) ps -a

migrate: ## Run both one-shot migration containers (Flyway + Mongo) and exit
	$(COMPOSE) up --build --no-log-prefix documentation-migrate alert-migrate

dlq-replay: ## Move dead-lettered events back to alert-service's queue (LIMIT=n for at most n; needs the stack up)
	$(COMPOSE) run --rm --no-deps alert-migrate replay-dead-letters $(LIMIT)

db-shell: ## psql into the documentation-service database
	$(COMPOSE) exec postgres sh -c 'psql -U "$$POSTGRES_USER" -d "$$POSTGRES_DB"'

mongo-shell: ## mongosh into the alert-service database
	$(COMPOSE) exec mongo mongosh "$${MONGO_DATABASE:-carevoice_alerts}"

redis-cli: ## redis-cli against the shared Redis
	$(COMPOSE) exec redis redis-cli

rabbitmq-ui: ## Print the RabbitMQ management UI URL
	@echo "RabbitMQ management: http://localhost:15672 (user/password: RABBITMQ_USER / RABBITMQ_PASSWORD from .env)"
	@echo "Prometheus metrics:  http://localhost:15692/metrics"

grafana: ## Print the Grafana URL (otel-lgtm)
	@echo "Grafana: http://localhost:3000 (admin / admin on first login)"

observability-reset: ## Delete ALL logs, metrics and traces (otel-lgtm volume) and start empty; dashboards come back from deploy/grafana
	$(COMPOSE) rm --stop --force otel-lgtm
	docker volume rm --force $$(docker volume ls -q --filter label=com.docker.compose.volume=otel-lgtm-data)
	$(COMPOSE) up -d otel-lgtm
	@echo "otel-lgtm restarted with empty Loki, Prometheus and Tempo. Grafana is routable once healthy (about a minute)."

monitoring-up: ## Start Uptime Kuma and create its monitors + status page (profile monitoring)
	$(COMPOSE) --profile monitoring up -d uptime-kuma
	$(COMPOSE) --profile monitoring run --rm uptime-kuma-setup
	@echo "Uptime Kuma:  http://localhost:3001  (login: KUMA_USERNAME / KUMA_PASSWORD from .env, default admin / admin)"
	@echo "Status page:  http://localhost:3001/status/carevoice"

admin-up: ## Start pgAdmin (http://localhost:5050) and mongo-express (http://localhost:8082) (profile admin)
	$(COMPOSE) --profile admin up -d pgadmin mongo-express
	@echo "pgAdmin:       http://localhost:5050   (server pre-registered; password = POSTGRES_PASSWORD from .env)"
	@echo "mongo-express: http://localhost:8082   (login admin / MONGO_EXPRESS_PASSWORD, default admin)"

tools-up: ## Start Homepage, one start page with every UI of the stack (profile tools; config in deploy/homepage)
	$(COMPOSE) --profile tools up -d homepage
	@echo "Homepage: http://home.carevoice.127.0.0.1.nip.io (add :CAREVOICE_HTTP_PORT if not 80) or http://localhost:3002"
	@echo "Uptime Kuma (make monitoring-up) and the database UIs (make admin-up) show as down until started."

## ---- Security ------------------------------------------------------------------------------------------

security-scan: ## ZAP API scan of both services, Trivy image CVEs, gitleaks (needs the stack running; reports in build/security)
	bash scripts/security-scan.sh

## ---- Databases ------------------------------------------------------------------------------------------

db-bench: ## Index benchmark: 1M observations, 300k alerts, each index vs its alternatives (ROWS=, ALERTS=; report in build/db-bench)
	COMPOSE="$(COMPOSE)" bash scripts/db-bench.sh

## ---- Demo ----------------------------------------------------------------------------------------------

STEP ?= 1

demo: ## Interactive live demo, one step at a time (STEP=7 to start later; docs/walkthrough.md)
	COMPOSE="$(COMPOSE)" bash scripts/demo.sh $(STEP)

demo-check: ## Is the stack ready for the demo: health of both services and the demo logins
	COMPOSE="$(COMPOSE)" bash scripts/demo.sh check

reset-demo: ## Purge all demo data (Postgres, Mongo, queues, Redis keys) and seed it fresh; keeps volumes and schema
	COMPOSE="$(COMPOSE)" bash scripts/reset-demo.sh

## ---- Local Kubernetes (k3d) --------------------------------------------------------
# Needs docker, k3d, kubectl and helm. Cluster config, issuers and demo databases live in deploy/k8s,
# the application chart in deploy/helm/carevoice. Secrets come from .env (cp .env.example .env).

K8S_CLUSTER      ?= carevoice
K8S_NAMESPACE    ?= carevoice
K8S_TAG          ?= local
CERT_MANAGER_VER ?= v1.21.2
CNPG_VER         ?= 1.30.1
RABBITMQ_OP_VER  ?= v2.23.0
BARMAN_PLUGIN_VER ?= v0.15.1
HELM_TIMEOUT     ?= 10m

k8s-up: ## Create the k3d cluster, operators, databases, Grafana, import images, deploy the chart (scripts/k8s-up.sh)
	K8S_CLUSTER=$(K8S_CLUSTER) K8S_NAMESPACE=$(K8S_NAMESPACE) K8S_TAG=$(K8S_TAG) CERT_MANAGER_VER=$(CERT_MANAGER_VER) \
	  CNPG_VER=$(CNPG_VER) RABBITMQ_OP_VER=$(RABBITMQ_OP_VER) BARMAN_PLUGIN_VER=$(BARMAN_PLUGIN_VER) \
	  HELM_TIMEOUT=$(HELM_TIMEOUT) bash scripts/k8s-up.sh

k8s-pg-restore-drill: ## Prove the Postgres backups: write a row, restore to a time before it into a scratch cluster, check (KEEP=1 keeps it)
	K8S_NAMESPACE=$(K8S_NAMESPACE) KEEP=$(KEEP) bash scripts/k8s-pg-restore-drill.sh

k8s-down: ## Delete the local k3d cluster (all data in it is lost; the image caches are kept)
	k3d cluster delete $(K8S_CLUSTER)

k8s-cache-clean: ## Delete the pull-through image caches (the next k8s-up downloads every image again)
	-k3d registry delete k3d-carevoice-cache-dockerio k3d-carevoice-cache-ghcr k3d-carevoice-cache-quay
	-docker volume rm carevoice-cache-dockerio-data carevoice-cache-ghcr-data carevoice-cache-quay-data

k8s-status: ## Show nodes, pods, jobs, HPAs, ingresses and certificates of the local cluster
	kubectl get nodes -o wide
	kubectl -n $(K8S_NAMESPACE) get pods,jobs,hpa,pdb,ingress -o wide
	-kubectl -n $(K8S_NAMESPACE) get clusters.postgresql.cnpg.io,poolers.postgresql.cnpg.io,rabbitmqclusters,certificates
	-kubectl -n $(K8S_NAMESPACE) get objectstores.barmancloud.cnpg.io,scheduledbackups.postgresql.cnpg.io,backups.postgresql.cnpg.io
	-kubectl -n observability get pods,ingress
	-helm -n $(K8S_NAMESPACE) status carevoice
