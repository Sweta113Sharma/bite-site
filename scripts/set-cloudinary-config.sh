#!/usr/bin/env bash
#
# Points BiteSite's file uploads at Cloudinary instead of the container's own disk.
#
# Why this matters: Azure App Service replaces the container filesystem on every deploy,
# so with the default storage-type=local every menu photo a canteen uploads is lost the
# next time you ship. Cloudinary keeps them.
#
#   bash scripts/set-cloudinary-config.sh
#
# CloudinaryFileStorageService is a @ConditionalOnProperty bean that THROWS during
# startup if storage-type=cloudinary and any credential is blank. A wrong value here
# does not degrade — it stops the app booting. So the credentials are proven against
# Cloudinary's API, including a real upload and delete, before anything is written.
#
set -euo pipefail

APP_NAME="${APP_NAME:-bitesite-app}"
RESOURCE_GROUP="${RESOURCE_GROUP:-bitesite-rg}"
SITE_URL="${SITE_URL:-https://app.bitesite.in}"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
warn() { printf '\033[33m%s\033[0m\n' "$1"; }
fail() { printf '\033[31m%s\033[0m\n' "$1" >&2; exit 1; }

command -v az      >/dev/null || fail "Azure CLI not found."
command -v python3 >/dev/null || fail "python3 not found — needed for the credential test."
az account show >/dev/null 2>&1 || fail "Not logged in. Run: az login"

bold "==> Locating the web app"
az webapp show --name "$APP_NAME" --resource-group "$RESOURCE_GROUP" -o none 2>/dev/null \
  || fail "No web app '$APP_NAME' in '$RESOURCE_GROUP'."
echo "    app:            $APP_NAME"
echo "    resource group: $RESOURCE_GROUP"

cat <<'EOF'

  All three are on the Cloudinary dashboard home, under "Product Environment
  Credentials". The free tier is far more than a campus canteen will use.

    Cloud name   e.g. dxxxxxxxx
    API key      a long number
    API secret   click "reveal" next to it

EOF

while read -r -t 0; do read -r -n 10000 -s _discard || break; done 2>/dev/null || true

# Cloudinary shows the whole thing as one CLOUDINARY_URL, so accept that form and pull
# the parts out — pasting one string beats transcribing three and mixing up key/secret.
echo "  Paste the whole CLOUDINARY_URL, or leave blank to enter the three parts separately."
read -rs -p "  CLOUDINARY_URL (not echoed): " CLOUD_URL; echo

if [ -n "$CLOUD_URL" ]; then
  case "$CLOUD_URL" in
    cloudinary://*:*@*) ;;
    *) fail "That does not look like a CLOUDINARY_URL. Expected cloudinary://key:secret@cloudname" ;;
  esac
  case "$CLOUD_URL" in
    *"<your_api_key>"*|*"<your_api_secret>"*|*"<"*">"*)
      fail "That is the template with placeholders still in it. Reveal the real key and secret on the Cloudinary dashboard first." ;;
  esac
  STRIPPED="${CLOUD_URL#cloudinary://}"
  API_KEY="${STRIPPED%%:*}"
  REST="${STRIPPED#*:}"
  API_SECRET="${REST%%@*}"
  CLOUD_NAME="${REST#*@}"
  echo "    parsed — cloud '$CLOUD_NAME', key ending ...${API_KEY: -4}"
else
  read -r  -p "  CLOUDINARY_CLOUD_NAME: " CLOUD_NAME
  read -r  -p "  CLOUDINARY_API_KEY: " API_KEY
  read -rs -p "  CLOUDINARY_API_SECRET (not echoed): " API_SECRET; echo
fi

[ -n "$CLOUD_NAME" ] || fail "Cloud name cannot be empty."
[ -n "$API_KEY" ]    || fail "API key cannot be empty."
[ -n "$API_SECRET" ] || fail "API secret cannot be empty."
[ "$API_KEY" != "$API_SECRET" ] || fail "Key and secret are identical — check what you pasted."

echo
bold "==> Testing the credentials"
echo "    authenticate -> upload a 1x1 image -> delete it again"

CLOUD_NAME="$CLOUD_NAME" API_KEY="$API_KEY" API_SECRET="$API_SECRET" \
python3 - <<'PYEOF' || fail "Cloudinary rejected these credentials. Nothing was changed."
import base64, hashlib, json, os, sys, time, urllib.request, urllib.parse, urllib.error

cloud  = os.environ["CLOUDINARY_CLOUD_NAME"] if "CLOUDINARY_CLOUD_NAME" in os.environ else os.environ["CLOUD_NAME"]
key    = os.environ["API_KEY"]
secret = os.environ["API_SECRET"]
base   = f"https://api.cloudinary.com/v1_1/{cloud}"

def call(url, data=None, auth=False):
    req = urllib.request.Request(url, data=data)
    if auth:
        tok = base64.b64encode(f"{key}:{secret}".encode()).decode()
        req.add_header("Authorization", "Basic " + tok)
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.loads(r.read().decode())

# 1. Authentication.
try:
    ping = call(f"{base}/ping", auth=True)
    if ping.get("status") != "ok":
        print(f"    unexpected ping response: {ping}", file=sys.stderr); sys.exit(1)
    print(f"    authenticated against cloud '{cloud}'")
except urllib.error.HTTPError as e:
    if e.code == 401:
        print("    AUTH FAILED — key/secret rejected. Check you revealed the full secret.", file=sys.stderr)
    elif e.code == 404:
        print(f"    NO SUCH CLOUD — '{cloud}' does not exist. Check the cloud name.", file=sys.stderr)
    else:
        print(f"    HTTP {e.code}: {e.read().decode()[:200]}", file=sys.stderr)
    sys.exit(1)
except Exception as e:
    print(f"    FAILED — {type(e).__name__}: {e}", file=sys.stderr); sys.exit(1)

# 2. A real signed upload — this is what the app does, and it needs more than read access.
PIXEL = ("data:image/gif;base64,"
         "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
ts = str(int(time.time()))
pid = f"bitesite-preflight-{ts}"
to_sign = f"public_id={pid}&timestamp={ts}{secret}"
sig = hashlib.sha1(to_sign.encode()).hexdigest()
form = urllib.parse.urlencode({
    "file": PIXEL, "public_id": pid, "timestamp": ts, "api_key": key, "signature": sig,
}).encode()
try:
    up = call(f"{base}/image/upload", data=form)
    print(f"    uploaded a test image ({up.get('bytes')} bytes)")
except urllib.error.HTTPError as e:
    print(f"    UPLOAD REFUSED — HTTP {e.code}: {e.read().decode()[:200]}", file=sys.stderr)
    print("    The key authenticates but cannot write. Check it is not a restricted key.", file=sys.stderr)
    sys.exit(1)

# 3. Clean up, so the preflight leaves nothing behind.
ts2 = str(int(time.time()))
sig2 = hashlib.sha1(f"public_id={pid}&timestamp={ts2}{secret}".encode()).hexdigest()
form2 = urllib.parse.urlencode({
    "public_id": pid, "timestamp": ts2, "api_key": key, "signature": sig2,
}).encode()
try:
    res = call(f"{base}/image/destroy", data=form2)
    print(f"    deleted it again ({res.get('result')})")
except Exception:
    print(f"    NOTE: could not delete the test image '{pid}' — remove it manually.", file=sys.stderr)
PYEOF

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
      UPLOAD_STORAGE_TYPE=cloudinary \
      CLOUDINARY_CLOUD_NAME="$CLOUD_NAME" \
      CLOUDINARY_API_KEY="$API_KEY" \
      CLOUDINARY_API_SECRET="$API_SECRET" \
  --output none

echo "    done — 4 settings applied, app restarting."

echo
bold "==> Waiting for it to come back"
HEALTHY=no
for i in $(seq 1 40); do
  if curl -fsS --max-time 10 "$SITE_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    HEALTHY=yes; echo "    healthy after ~$((i * 5))s"; break
  fi
  sleep 5
done

if [ "$HEALTHY" != "yes" ]; then
  warn "    NOT healthy after 200s."
  warn "    CloudinaryFileStorageService throws at startup if a credential is wrong, so"
  warn "    this is the failure that stops the app booting. Check the logs:"
  warn "      az webapp log tail -n $APP_NAME -g $RESOURCE_GROUP"
  warn "    To roll back immediately:"
  warn "      az webapp config appsettings set -n $APP_NAME -g $RESOURCE_GROUP --settings UPLOAD_STORAGE_TYPE=local"
  exit 1
fi

cat <<EOF

$(bold "==> What changed")

  Menu photos and college logos now go to Cloudinary rather than the container's
  own disk, so they survive deploys instead of vanishing on the next one.

$(bold "==> Confirm it")

  Sign in to an outlet account, edit a menu item, upload a photo, and check the
  image URL it renders — it should point at res.cloudinary.com, not /uploads/.

  Photos uploaded BEFORE this change are already gone; they were on a filesystem
  that has been replaced since. Canteens will need to re-upload them once.
EOF
