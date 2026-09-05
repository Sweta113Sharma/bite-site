#!/usr/bin/env bash
#
# Points BiteSite's error reporting at Sentry.
#
# Until this is set, production failures are invisible: the SDK no-ops with a blank DSN,
# so a 500 on checkout looks exactly like silence. That matters more now that real card
# payments run through the app.
#
#   bash scripts/set-sentry-config.sh
#
# The DSN is not a secret in the way an API key is — it only permits *writing* events,
# which is why it ships in browser bundles all over the web. It is still prompted rather
# than passed as an argument so it stays out of shell history.
#
set -euo pipefail

APP_NAME="${APP_NAME:-bitesite-app}"
RESOURCE_GROUP="${RESOURCE_GROUP:-bitesite-rg}"
SITE_URL="${SITE_URL:-https://app.bitesite.in}"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
warn() { printf '\033[33m%s\033[0m\n' "$1"; }
fail() { printf '\033[31m%s\033[0m\n' "$1" >&2; exit 1; }

command -v az      >/dev/null || fail "Azure CLI not found."
command -v python3 >/dev/null || fail "python3 not found — needed for the test event."
az account show >/dev/null 2>&1 || fail "Not logged in. Run: az login"

bold "==> Locating the web app"
az webapp show --name "$APP_NAME" --resource-group "$RESOURCE_GROUP" -o none 2>/dev/null \
  || fail "No web app '$APP_NAME' in '$RESOURCE_GROUP'."
echo "    app:            $APP_NAME"
echo "    resource group: $RESOURCE_GROUP"

cat <<'EOF'

  Create a project in Sentry first — platform "Java / Spring Boot". The DSN is on
  Settings > Projects > [your project] > Client Keys (DSN), and looks like:

    https://abc123...@o123456.ingest.de.sentry.io/7891011

EOF

while read -r -t 0; do read -r -n 10000 -s _discard || break; done 2>/dev/null || true

read -r -p "  SENTRY_DSN: " DSN
[ -n "$DSN" ] || fail "DSN cannot be empty."

case "$DSN" in
  https://*@*/*) ;;
  *) fail "That does not look like a DSN. Expected https://<key>@<host>/<project-id>" ;;
esac
case "$DSN" in
  *"<"*">"*) fail "That still has placeholders in it — copy the real DSN from Client Keys." ;;
esac

echo
read -r -p "  SENTRY_ENVIRONMENT [production]: " ENVIRONMENT
ENVIRONMENT="${ENVIRONMENT:-production}"

# Performance tracing is separate from error reporting and consumes a different, smaller
# quota. Errors are the thing you actually need on day one, so this stays off unless
# asked for.
read -r -p "  SENTRY_TRACES_SAMPLE_RATE [0.0 = errors only]: " TRACES
TRACES="${TRACES:-0.0}"

echo
bold "==> Sending a test event"

DSN="$DSN" ENVIRONMENT="$ENVIRONMENT" python3 - <<'PYEOF' || fail "Sentry did not accept the event. Nothing was changed."
import json, os, re, sys, time, urllib.request, urllib.error, uuid

dsn = os.environ["DSN"]
env = os.environ["ENVIRONMENT"]

# https://PUBLIC_KEY@HOST/PROJECT_ID
m = re.match(r"https://([^@]+)@([^/]+)/(.+)$", dsn)
if not m:
    print("    could not parse the DSN", file=sys.stderr); sys.exit(1)
key, host, project = m.group(1), m.group(2), m.group(3).strip("/")
print(f"    project {project} at {host}")

url = f"https://{host}/api/{project}/store/"
event = {
    "event_id": uuid.uuid4().hex,
    "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "platform": "other",
    "level": "info",
    "logger": "bitesite.preflight",
    "environment": env,
    "message": {"formatted": "BiteSite Sentry pre-flight — if you can see this, error reporting works."},
    "tags": {"source": "set-sentry-config.sh"},
}
req = urllib.request.Request(
    url, data=json.dumps(event).encode(),
    headers={
        "Content-Type": "application/json",
        "X-Sentry-Auth": f"Sentry sentry_version=7, sentry_client=bitesite-preflight/1.0, sentry_key={key}",
    })
try:
    with urllib.request.urlopen(req, timeout=25) as r:
        body = json.loads(r.read().decode())
        print(f"    accepted — event id {body.get('id', '?')}")
except urllib.error.HTTPError as e:
    detail = e.read().decode()[:200]
    if e.code in (401, 403):
        print(f"    REJECTED ({e.code}) — the DSN key is wrong or the project was deleted.", file=sys.stderr)
    elif e.code == 429:
        print("    RATE LIMITED — the project is over quota. The DSN itself is valid.", file=sys.stderr)
        sys.exit(0)
    else:
        print(f"    HTTP {e.code}: {detail}", file=sys.stderr)
    sys.exit(1)
except Exception as e:
    print(f"    FAILED — {type(e).__name__}: {e}", file=sys.stderr); sys.exit(1)
PYEOF

echo
warn "==> Check the event landed in Sentry before continuing."
warn "    It appears under Issues, tagged environment=$ENVIRONMENT. Give it ~15 seconds."
read -r -p "    Can you see it? [y/N] " GOT_IT
[ "$GOT_IT" = "y" ] || [ "$GOT_IT" = "Y" ] || fail "Aborted — nothing changed. An accepted event that never appears means the project is misrouted, and you would be trusting reporting that does not work."

echo
warn "==> Applying these settings RESTARTS $APP_NAME (~40s of downtime)."
warn "    You are taking live payments — pick a quiet moment."
read -r -p "    Continue? [y/N] " CONFIRM
[ "$CONFIRM" = "y" ] || [ "$CONFIRM" = "Y" ] || fail "Aborted. Nothing changed."

echo
bold "==> Setting app settings"
az webapp config appsettings set \
  --name "$APP_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --settings \
      SENTRY_DSN="$DSN" \
      SENTRY_ENVIRONMENT="$ENVIRONMENT" \
      SENTRY_TRACES_SAMPLE_RATE="$TRACES" \
  --output none

echo "    done — 3 settings applied, app restarting."

echo
bold "==> Waiting for it to come back"
for i in $(seq 1 40); do
  if curl -fsS --max-time 10 "$SITE_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    echo "    healthy after ~$((i * 5))s"; break
  fi
  sleep 5
  [ "$i" = "40" ] && warn "    not healthy after 200s — az webapp log tail -n $APP_NAME -g $RESOURCE_GROUP"
done

cat <<EOF

$(bold "==> What changed")

  Unhandled exceptions now reach Sentry tagged environment=$ENVIRONMENT, instead of
  going only to a log file nobody is reading.

  send-default-pii stays false — student names, emails and addresses are NOT sent.
  Given the consent records this app keeps, that default is deliberate; do not flip
  it without thinking about what leaves the country.

$(bold "==> Worth doing next")

  Set an alert rule in Sentry so a spike pages you rather than waiting to be noticed:
  Alerts > Create Alert > Issues > "when an issue is first seen" -> email.

  The one that matters most is anything thrown from the payment or webhook paths.
EOF
