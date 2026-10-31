#!/usr/bin/env bash
# Security scan of the local CareVoice stack (make security-scan). Reports go to build/security/.
#
#   1. OWASP ZAP API scan of both services, driven by their OpenAPI specs, authenticated with demo-user JWTs.
#      Runs inside the compose network and reaches the services through Traefik (traefik:8080 / traefik:8081).
#   2. Trivy: known HIGH/CRITICAL CVEs in both images (OS packages and every jar).
#   3. gitleaks: secrets in the git history.
#
# Only for the local stack you own. Needs: docker, curl, jq, the stack running (make up-fast), .env with DEMO_PASSWORD.
# Exit code: 0 when nothing was found at failure level, 1 otherwise (ZAP FAIL, Trivy findings, leaked secrets).
set -uo pipefail

cd "$(dirname "$0")/.."
OUT="$PWD/build/security"
mkdir -p "$OUT" && chmod 777 "$OUT"   # ZAP writes as its own non-root user

ZAP_IMAGE=ghcr.io/zaproxy/zaproxy:2.16.0
TRIVY_IMAGE=aquasec/trivy:0.58.1
GITLEAKS_IMAGE=zricethezav/gitleaks:v8.21.2
NETWORK=carevoice

SUMMARY=""
failed=0
# macOS ships bash 3.2 (no associative arrays): collect the summary as text.
result() { SUMMARY="$SUMMARY$(printf '  %-30s %s' "$1" "$2")"$'\n'; }

need() { command -v "$1" >/dev/null || { echo "missing: $1"; exit 2; }; }
need docker; need curl; need jq

curl -fsS -o /dev/null localhost:8080/readyz || { echo "stack not running: start it with 'make up-fast'"; exit 2; }
PW=$(grep '^DEMO_PASSWORD=' .env | cut -d= -f2-)
token() {
  curl -fsS localhost:8080/v1/auth/token -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | jq -r .accessToken
}
NURSE=$(token nurse.anna) && LEAD=$(token lead.clara) || { echo "login failed: check DEMO_PASSWORD in .env"; exit 2; }

# ---- 1. ZAP API scans -------------------------------------------------------------------------------------------------
# zap-api-scan exit codes: 0 pass, 1 at least one FAIL, 2 only WARN, 3 scan error.
zap_scan() { # name, host:port, token
  echo "==> ZAP API scan: $1"
  docker run --rm --network "$NETWORK" -v "$OUT:/zap/wrk:rw" \
    -e ZAP_AUTH_HEADER=Authorization -e ZAP_AUTH_HEADER_VALUE="Bearer $3" \
    "$ZAP_IMAGE" zap-api-scan.py -t "http://$2/openapi.yaml" -f openapi -O "$2" \
    -r "zap-$1.html" -J "zap-$1.json" -I >"$OUT/zap-$1.log" 2>&1
  local code=$?
  case $code in
    0) result "zap-$1" "pass" ;;
    2) result "zap-$1" "warnings only (see report)" ;;
    1) result "zap-$1" "FAIL findings"; failed=1 ;;
    *) result "zap-$1" "scan error $code (see zap-$1.log)"; failed=1 ;;
  esac
}
# -I: do not fail on warnings; FAIL-level alerts still fail.
zap_scan documentation-service traefik:8080 "$NURSE"
zap_scan alert-service traefik:8081 "$LEAD"

# ---- 2. Trivy image scans -----------------------------------------------------------------------------------------------
for image in documentation-service alert-service; do
  echo "==> Trivy: carevoice/$image:local"
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v carevoice-trivy-cache:/root/.cache -v "$OUT:/out" \
    "$TRIVY_IMAGE" image --quiet --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 \
    --format table --output "/out/trivy-$image.txt" "carevoice/$image:local"
  if [ $? -eq 0 ]; then result "trivy-$image" "no fixable HIGH/CRITICAL"; else result "trivy-$image" "FINDINGS (trivy-$image.txt)"; failed=1; fi
done

# ---- 3. gitleaks ------------------------------------------------------------------------------------------------------
echo "==> gitleaks: git history"
docker run --rm -v "$PWD:/repo:ro" -v "$OUT:/out" "$GITLEAKS_IMAGE" \
  detect --source /repo --redact --report-format json --report-path /out/gitleaks.json --no-banner >"$OUT/gitleaks.log" 2>&1
if [ $? -eq 0 ]; then result "gitleaks" "no secrets"; else result "gitleaks" "LEAKS or error (gitleaks.json / gitleaks.log)"; failed=1; fi

# ---- Summary ----------------------------------------------------------------------------------------------------------
echo
echo "Security scan summary (reports in build/security/):"
printf "%s" "$SUMMARY"
exit $failed
