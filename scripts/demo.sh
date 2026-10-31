#!/usr/bin/env bash
# Interactive live demo of CareVoice against the running compose stack (docs/walkthrough.md, section 3).
#
#   make demo            all steps, one at a time (Enter = next, q = quit)
#   make demo STEP=7     start at step 7
#   make demo-check      health of the stack and the demo logins only
#
# Every step prints the request it sends, the status, the headers that matter and the (filtered) JSON answer,
# followed by the point it proves. Steps 11, 13 and 14 stop containers; they ask before doing so.
# Needs curl and jq. Bash 3.2 compatible (macOS).
set -uo pipefail # no -e: a failing step must stay visible on screen, not end the demo

DOC=${DOC:-http://localhost:8080}
ALERT=${ALERT:-http://localhost:8081}
COMPOSE=${COMPOSE:-docker compose}
START=${1:-1}

PW=${DEMO_PASSWORD:-$(sed -n 's/^DEMO_PASSWORD=//p' .env 2>/dev/null | tail -1 | tr -d "'\"")}
PW=${PW:-demo-password-change-me}

TMP=$(mktemp -d)
SSE_PID=""
cleanup() {
  [ -n "$SSE_PID" ] && kill "$SSE_PID" 2>/dev/null
  rm -rf "$TMP"
}
trap cleanup EXIT

B=$'\033[1m' C=$'\033[36m' G=$'\033[32m' Y=$'\033[33m' R=$'\033[31m' D=$'\033[2m' N=$'\033[0m'

for tool in curl jq; do
  command -v "$tool" >/dev/null || { echo "demo needs $tool (brew install $tool)" >&2; exit 1; }
done

newkey() {
  if command -v uuidgen >/dev/null; then uuidgen | tr '[:upper:]' '[:lower:]'; else python3 -c 'import uuid; print(uuid.uuid4())'; fi
}

step() { printf '\n%s━━ Step %s: %s ━━%s\n' "$B$C" "$1" "$2" "$N"; }
say() { printf '%sSay:%s %s\n' "$Y$B" "$N$Y" "$*"; printf '%s' "$N"; }
note() { printf '%s%s%s\n' "$D" "$*" "$N"; }
pause() {
  [ "${NONINTERACTIVE:-0}" = 1 ] && return 0
  printf '%s[Enter] next   [q] quit%s ' "$D" "$N"
  read -r answer
  [ "$answer" = q ] && exit 0
  return 0
}
confirm() {
  [ "${NONINTERACTIVE:-0}" = 1 ] && return 0
  printf '%s%s Continue? [y/N]%s ' "$R$B" "$1" "$N"
  read -r answer
  [ "$answer" = y ] || [ "$answer" = Y ]
}

# req METHOD URL USER [BODY] [HEADER...]: USER is anna, ben, clara or none. Sets STATUS and REQ_ID; the body is in
# $TMP/b. Set JQ to filter what is shown (the full body stays in $TMP/b).
req() {
  local method=$1 url=$2 who=$3 body=${4:-} token="" header
  shift 3
  [ $# -gt 0 ] && shift
  case $who in
    anna) token=$ANNA ;;
    ben) token=$BEN ;;
    clara) token=$CLARA ;;
  esac
  local args=(-s -X "$method" -D "$TMP/h" -o "$TMP/b" -w '%{http_code}' --max-time 15)
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data "$body")
  printf '%s→ %s %s%s  %s(as %s)%s\n' "$B" "$method" "$url" "$N" "$D" "$who" "$N"
  for header in "$@"; do
    args+=(-H "$header")
    printf '  %s%s%s\n' "$D" "$header" "$N"
  done
  [ -n "$body" ] && printf '  %s%s%s\n' "$D" "$body" "$N"
  STATUS=$(curl "${args[@]}" "$url")
  local color=$G
  case $STATUS in 4*) color=$Y ;; 5* | 000) color=$R ;; esac
  printf '%s← %s%s\n' "$color$B" "$STATUS" "$N"
  grep -iE '^(location|etag|x-request-id|retry-after|link|x-total-count):' "$TMP/h" 2>/dev/null |
    tr -d '\r' | sed 's/^/  /'
  REQ_ID=$(grep -i '^x-request-id:' "$TMP/h" 2>/dev/null | head -1 | cut -d' ' -f2 | tr -d '\r')
  if [ -s "$TMP/b" ]; then
    if jq -e . "$TMP/b" >/dev/null 2>&1; then
      jq "${JQ:-.}" "$TMP/b" 2>/dev/null | head -40
    else
      head -c 600 "$TMP/b"
      echo
    fi
  fi
}

transcript() { printf '{"transcript":"%s"}' "$1"; }
login() {
  curl -s --max-time 10 "$DOC/v1/auth/token" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | jq -r '.accessToken // empty'
}
# Quiet helpers for state a later step needs when the demo starts in the middle.
quiet_post() {
  curl -s --max-time 15 -X POST "$DOC/v1/residents/res-1/transcripts" -H "Authorization: Bearer $ANNA" \
    -H "Idempotency-Key: $(newkey)" -H 'Content-Type: application/json' --data "$(transcript "$1")"
}
first_open_alert() {
  curl -s --max-time 10 "$ALERT/v1/alerts?status=OPEN&limit=20" -H "Authorization: Bearer $CLARA" |
    jq -r --arg rule "${1:-}" '[.items[] | select($rule == "" or .rule == $rule)][0].id // empty'
}
wait_for_alert() { # rule, seconds
  local i=0 id=""
  while [ $i -lt "$2" ]; do
    id=$(first_open_alert "$1")
    [ -n "$id" ] && { echo "$id"; return 0; }
    sleep 1
    i=$((i + 1))
  done
  return 1
}
wait_ready() { # url, seconds
  local i=0
  while [ $i -lt "$2" ]; do
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$1")" = 200 ] && return 0
    sleep 1
    i=$((i + 1))
  done
  return 1
}

# ---- check --------------------------------------------------------------------------------------------------------
check() {
  local ok=0 url user
  for url in "$DOC/livez" "$DOC/readyz" "$ALERT/livez" "$ALERT/readyz"; do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url")" = 200 ]; then
      printf '%sOK%s   %s\n' "$G" "$N" "$url"
    else
      printf '%sFAIL%s %s\n' "$R" "$N" "$url"
      ok=1
    fi
  done
  for user in nurse.anna nurse.ben lead.clara; do
    if [ -n "$(login "$user")" ]; then
      printf '%sOK%s   login %s\n' "$G" "$N" "$user"
    else
      printf '%sFAIL%s login %s (DEMO_PASSWORD in .env?)\n' "$R" "$N" "$user"
      ok=1
    fi
  done
  [ $ok = 0 ] && echo "Ready for the demo." || echo "Fix the failures above (make ps, make logs)."
  return $ok
}

if [ "$START" = check ]; then
  check
  exit $?
fi

ANNA=$(login nurse.anna)
BEN=$(login nurse.ben)
CLARA=$(login lead.clara)
if [ -z "$ANNA" ] || [ -z "$CLARA" ]; then
  echo "Cannot log in as the demo users. Is the stack up (make up-fast) and DEMO_PASSWORD right?" >&2
  exit 1
fi
KEY=$(newkey)
FIRST_REQ_ID=""
REVIEW_ID=""
REVIEW_VERSION=0
ALERT_ID=""

run_step() {
  case $1 in
    1)
      step 1 "Health: liveness versus readiness"
      req GET "$DOC/livez" none
      req GET "$DOC/readyz" none
      req GET "$ALERT/readyz" none
      say "Liveness never checks dependencies, readiness does: a database outage stops traffic to the pod" \
        "instead of restarting healthy pods in a loop. Postgres is critical; Redis and RabbitMQ only degrade."
      ;;
    2)
      step 2 "Log in: tenant, ward and roles live in the token"
      note "POST $DOC/v1/auth/token as nurse.anna (password hidden). Claims of the token:"
      echo "$ANNA" | jq -R 'split(".")[1] | gsub("-"; "+") | gsub("_"; "/") | . + ("=" * ((4 - length % 4) % 4)) | @base64d | fromjson'
      say "15-minute JWT. Every SQL query filters on tenant AND ward: isolation in the query, not only in code."
      ;;
    3)
      step 3 "Document a transcript"
      JQ='{transcriptId, observations: [.observations[] | {vital, source, confidence, status}], rejected}' \
        req POST "$DOC/v1/residents/res-1/transcripts" anna \
        "$(transcript 'Mrs. Smith, blood pressure 120 over 75, pulse 80, temperature 37.2 degrees')" \
        "Idempotency-Key: $KEY"
      FIRST_REQ_ID=$REQ_ID
      say "Rules, not AI: deterministic, instant, free. One transaction stores the encrypted transcript," \
        "the observations, the outbox events and the audit row."
      ;;
    4)
      step 4 "Retries must be safe: same key"
      JQ='{transcriptId}' req POST "$DOC/v1/residents/res-1/transcripts" anna \
        "$(transcript 'Mrs. Smith, blood pressure 120 over 75, pulse 80, temperature 37.2 degrees')" \
        "Idempotency-Key: $KEY"
      note "Same key, different body:"
      JQ='{status, title, detail}' req POST "$DOC/v1/residents/res-1/transcripts" anna \
        "$(transcript 'Mrs. Smith, pulse 95')" "Idempotency-Key: $KEY"
      say "200 with the original response: the phone lost the answer, not the request. 409: a client bug." \
        "A unique constraint decides, so two pods receiving the retry at once cannot both store it."
      ;;
    5)
      step 5 "Uncertain and impossible values"
      JQ='[.observations[] | {id, vital, confidence, status, version}]' \
        req POST "$DOC/v1/residents/res-1/transcripts" anna "$(transcript 'Mr. Jones, 135/85, temperature 101')" \
        "Idempotency-Key: $(newkey)"
      REVIEW_ID=$(jq -r '[.observations[] | select(.status == "NEEDS_REVIEW")][0].id // empty' "$TMP/b")
      REVIEW_VERSION=$(jq -r '[.observations[] | select(.status == "NEEDS_REVIEW")][0].version // 0' "$TMP/b")
      JQ='{kept: [.observations[].vital], rejected}' \
        req POST "$DOC/v1/residents/res-1/transcripts" anna \
        "$(transcript 'Mrs. Smith, blood pressure 12 over 10, pulse 80')" "Idempotency-Key: $(newkey)"
      say "Uncertain values wait for a nurse (NEEDS_REVIEW). 12 over 10 never reaches the database:" \
        "a wrong value in a care record is worse than a missing one."
      ;;
    6)
      step 6 "Isolation, tested like an attacker would"
      JQ='{status, title}' req GET "$DOC/v1/residents/res-1" ben
      JQ='{status, title}' req GET "$ALERT/v1/alerts" anna
      say "404, not 403, across wards: ids cannot be probed. Roles decide what you may do;" \
        "the scope decides what exists for you."
      ;;
    7)
      step 7 "The ward's live feed (SSE) across pods"
      note "Opening the stream as nurse.anna, then documenting a critical blood pressure..."
      curl -sN --max-time 8 "$DOC/v1/observations/stream" -H "Authorization: Bearer $ANNA" >"$TMP/sse" 2>/dev/null &
      SSE_PID=$!
      sleep 1
      JQ='[.observations[].vital]' req POST "$DOC/v1/residents/res-1/transcripts" anna \
        "$(transcript 'Mrs. Smith, blood pressure 185 over 95, pulse 88')" "Idempotency-Key: $(newkey)"
      sleep 3
      note "Received on the stream:"
      grep -E '^(event|id):' "$TMP/sse" | head -6
      grep -E '^data:' "$TMP/sse" | head -2 | cut -c1-160
      kill "$SSE_PID" 2>/dev/null
      SSE_PID=""
      say "The stream is held by one pod, the POST may land on the other: Redis pub/sub fans it out." \
        "Pub/sub may lose messages; Last-Event-ID replays them from Postgres, so no sticky sessions."
      ;;
    8)
      step 8 "The alert arrives through the outbox and RabbitMQ"
      ALERT_ID=$(wait_for_alert HYPERTENSIVE_CRISIS 15) || note "(no crisis alert yet: run step 7 first)"
      JQ='[.items[] | {rule, severity, message, status}][0:3]' \
        req GET "$ALERT/v1/alerts?status=OPEN&limit=5" clara
      say "Event and observation commit in one transaction: no record without its alert. At-least-once" \
        "delivery, deduplicated by event id: a redelivery never creates a second alert."
      ;;
    9)
      step 9 "Protocol guidance (RAG) for the alert"
      [ -z "$ALERT_ID" ] && ALERT_ID=$(first_open_alert HYPERTENSIVE_CRISIS)
      [ -z "$ALERT_ID" ] && { quiet_post 'Mrs. Smith, blood pressure 185 over 95' >/dev/null; ALERT_ID=$(wait_for_alert HYPERTENSIVE_CRISIS 15); }
      JQ='{generatedBy, steps, sources: [.sources[] | {id, section, score}], disclaimer}' \
        req GET "$ALERT/v1/alerts/$ALERT_ID/guidance" clara
      say "Retrieval picks the protocol, the LLM may phrase only from its excerpts, every step cites one;" \
        "with Gemini the citation ids are an enum in the schema. A grounding check, then the protocol's own steps."
      ;;
    10)
      step 10 "Concurrency without locks: ETag and If-Match"
      [ -z "$ALERT_ID" ] && ALERT_ID=$(first_open_alert)
      JQ='{rule, status, version}' req GET "$ALERT/v1/alerts/$ALERT_ID" clara
      local etag
      etag=$(grep -i '^etag:' "$TMP/h" | cut -d' ' -f2 | tr -d '\r')
      JQ='{status, title}' req PATCH "$ALERT/v1/alerts/$ALERT_ID/acknowledge" clara
      JQ='{status, acknowledgedBy, version}' req PATCH "$ALERT/v1/alerts/$ALERT_ID/acknowledge" clara "" \
        "If-Match: $etag"
      etag=$(grep -i '^etag:' "$TMP/h" | cut -d' ' -f2 | tr -d '\r')
      JQ='{status, title}' req PATCH "$ALERT/v1/alerts/$ALERT_ID/acknowledge" clara "" "If-Match: $etag"
      note "The same on an observation: confirm the NEEDS_REVIEW value from step 5."
      if [ -z "$REVIEW_ID" ]; then
        quiet_post 'Mr. Jones, 135/85, temperature 101' >"$TMP/review"
        REVIEW_ID=$(jq -r '[.observations[] | select(.status == "NEEDS_REVIEW")][0].id // empty' "$TMP/review")
        REVIEW_VERSION=$(jq -r '[.observations[] | select(.status == "NEEDS_REVIEW")][0].version // 0' "$TMP/review")
      fi
      JQ='{status, title}' req PATCH "$DOC/v1/observations/$REVIEW_ID/confirm" anna "" 'If-Match: "99"'
      JQ='{status, confirmedBy, version}' req PATCH "$DOC/v1/observations/$REVIEW_ID/confirm" anna "" \
        "If-Match: \"${REVIEW_VERSION:-0}\""
      say "428 without If-Match, 200 with it, 409 when it is already done, 412 with a stale version:" \
        "two ward leads on two tablets, no row locks held across a human's think time."
      ;;
    11)
      step 11 "RabbitMQ goes down; documentation does not"
      if confirm "This stops RabbitMQ for about 20 seconds."; then
        $COMPOSE stop rabbitmq >/dev/null 2>&1
        JQ='[.observations[] | {vital, status}]' req POST "$DOC/v1/residents/res-1/transcripts" anna \
          "$(transcript 'Mrs. Smith, pulse 132')" "Idempotency-Key: $(newkey)"
        note "Outbox backlog while the broker is down:"
        curl -s "$DOC/metrics" | grep '^carevoice_outbox_pending' | head -3
        JQ='.checks' req GET "$DOC/readyz" none
        note "Starting RabbitMQ again; the relay drains the backlog..."
        $COMPOSE start rabbitmq >/dev/null 2>&1
        if wait_for_alert TACHYCARDIA 60 >/dev/null; then
          JQ='[.items[] | {rule, message}][0:3]' req GET "$ALERT/v1/alerts?status=OPEN&limit=5" clara
        else
          note "(no TACHYCARDIA alert yet; check RabbitMQ management and the outbox gauge)"
        fi
        say "Nurses kept documenting; the alert was delayed, never lost. The circuit breaker stopped" \
          "hammering the broker, and the outage did not use up the event's retry budget."
      fi
      ;;
    12)
      step 12 "Follow one request through both services"
      note "Grafana → Explore → Loki, query:"
      printf '  %s{service_name=~".+"} | requestId="%s"%s\n' "$B" "${FIRST_REQ_ID:-<X-Request-Id of step 3>}" "$N"
      note "Then click the traceId on any line to open the trace in Tempo. Filter by field: | result=\"error\""
      say "The request id travels with the event through the outbox and RabbitMQ, so the asynchronous part" \
        "still belongs to the nurse's request. Values are fields, never transcript text or names."
      ;;
    13)
      step 13 "Redis down (degradable), then Postgres down (critical)"
      if confirm "This stops Redis, then Postgres, for a few seconds each."; then
        $COMPOSE stop redis >/dev/null 2>&1
        JQ='{status, checks}' req GET "$DOC/readyz" none
        JQ='{observations: (.observations | length)}' req POST "$DOC/v1/residents/res-1/transcripts" anna \
          "$(transcript 'pulse 70')" "Idempotency-Key: $(newkey)"
        $COMPOSE start redis >/dev/null 2>&1
        $COMPOSE stop postgres >/dev/null 2>&1
        JQ='{status, checks}' req GET "$DOC/readyz" none
        JQ='{status, title, detail}' req POST "$DOC/v1/residents/res-1/transcripts" anna \
          "$(transcript 'pulse 70')" "Idempotency-Key: $(newkey)"
        note "Starting Postgres again..."
        $COMPOSE start postgres >/dev/null 2>&1
        wait_ready "$DOC/readyz" 60 && note "documentation-service is ready again, no redeploy needed."
        say "Redis down: rate limits fail open, caches use TTLs, still ready. Postgres down: 503 with" \
          "Retry-After and not ready; the client retries with the same key. Never lost or duplicated data."
      fi
      ;;
    14)
      step 14 "Kill a pod"
      if confirm "This kills one documentation-service replica."; then
        local victim
        victim=$($COMPOSE ps -q documentation-service | head -1)
        docker kill "$victim" >/dev/null && note "killed $victim"
        printf 'readyz through Traefik: '
        for _ in 1 2 3 4 5 6; do printf '%s ' "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$DOC/readyz")"; done
        echo
        $COMPOSE up -d documentation-service >/dev/null 2>&1 && note "replica restarted"
        say "Replicas are not cheaper, they cost memory. They buy surviving crashes, node drains and" \
          "deployments, and they force statelessness: shared state lives in Postgres and Redis."
      fi
      ;;
  esac
}

LAST=14
i=$START
while [ "$i" -le $LAST ]; do
  run_step "$i"
  [ "$i" -lt $LAST ] && pause
  i=$((i + 1))
done
printf '\n%sDemo finished.%s Deep dives: docs/walkthrough.md, section 3 ("Optional deep-dive demos").\n' "$B" "$N"
