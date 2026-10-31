"""
Uptime Kuma as code: creates the admin account (first run), the CareVoice monitors and the public status page.

Idempotent: monitors are matched by name, so running it again (every `make monitoring-up`) adds what is missing and
leaves existing monitors and their history alone. Runs in the one-shot `uptime-kuma-setup` container on the compose
network, so monitors use the internal service names (traefik, postgres, ...), not localhost.

Uses uptime-kuma-api (Socket.IO client for Uptime Kuma 1.23; Kuma has no REST API for monitors).
"""
import json
import os
import sys
import time

from uptime_kuma_api import MonitorType, UptimeKumaApi

URL = os.environ.get("KUMA_URL", "http://uptime-kuma:3001")
USER = os.environ["KUMA_USERNAME"]
PASSWORD = os.environ["KUMA_PASSWORD"]
DOMAIN = os.environ.get("CAREVOICE_DOMAIN", "carevoice.127.0.0.1.nip.io")
SLUG = "carevoice"

# (group on the status page, monitor definition)
MONITORS = [
    ("API", dict(type=MonitorType.HTTP, name="documentation-service /readyz", url="http://traefik:8080/readyz")),
    ("API", dict(type=MonitorType.HTTP, name="alert-service /readyz", url="http://traefik:8081/readyz")),
    # Liveness through the load balancer (each check reaches one replica); in Kubernetes the kubelet probes every pod.
    ("API", dict(type=MonitorType.HTTP, name="documentation-service /livez", url="http://traefik:8080/livez")),
    ("API", dict(type=MonitorType.HTTP, name="alert-service /livez", url="http://traefik:8081/livez")),
    ("API", dict(type=MonitorType.HTTP, name=f"Public API (api.{DOMAIN})", url="http://traefik/livez",
                 headers=json.dumps({"Host": f"api.{DOMAIN}"}))),
    ("Datastores", dict(type=MonitorType.PORT, name="PostgreSQL", hostname="postgres", port=5432)),
    ("Datastores", dict(type=MonitorType.PORT, name="MongoDB (rs0)", hostname="mongo", port=27017)),
    ("Datastores", dict(type=MonitorType.PORT, name="Redis", hostname="redis", port=6379)),
    ("Messaging", dict(type=MonitorType.PORT, name="RabbitMQ (AMQP)", hostname="rabbitmq", port=5672)),
    ("Messaging", dict(type=MonitorType.HTTP, name="RabbitMQ management", url="http://rabbitmq:15672")),
    ("Observability", dict(type=MonitorType.HTTP, name="Grafana", url="http://otel-lgtm:3000/api/health")),
]


def connect() -> UptimeKumaApi:
    """Uptime Kuma needs a few seconds after the container starts; retry for up to 2 minutes."""
    deadline = time.time() + 120
    while True:
        try:
            return UptimeKumaApi(URL, timeout=30)
        except Exception as e:  # noqa: BLE001 - any connection error means "not up yet"
            if time.time() > deadline:
                sys.exit(f"Uptime Kuma at {URL} not reachable: {e}")
            time.sleep(3)


api = connect()
try:
    if api.need_setup():
        api.setup(USER, PASSWORD)
        print(f"created admin user '{USER}'")
    api.login(USER, PASSWORD)

    existing = {m["name"]: m["id"] for m in api.get_monitors()}
    groups: dict[str, list[dict]] = {}
    for group, monitor in MONITORS:
        name = monitor["name"]
        if name in existing:
            monitor_id = existing[name]
            print(f"exists   {name}")
        else:
            monitor_id = api.add_monitor(interval=30, retryInterval=30, maxretries=1, **monitor)["monitorID"]
            print(f"created  {name}")
        groups.setdefault(group, []).append({"id": monitor_id})

    if SLUG not in {p["slug"] for p in api.get_status_pages()}:
        api.add_status_page(SLUG, "CareVoice Status")
    api.save_status_page(
        SLUG,
        title="CareVoice Status",
        description="Black-box checks of the CareVoice demo stack (docker compose).",
        publicGroupList=[{"name": g, "monitorList": ms} for g, ms in groups.items()],
    )
    print(f"status page: http://status.{DOMAIN}/status/{SLUG} (add :<CAREVOICE_HTTP_PORT> if not 80)")
finally:
    api.disconnect()
