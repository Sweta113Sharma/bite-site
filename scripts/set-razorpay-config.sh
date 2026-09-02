#!/usr/bin/env bash
#
# Sets the Razorpay credentials on the BiteSite Azure Web App.
#
# Run it from anywhere you have the Azure CLI logged in — your laptop or the
# Azure Cloud Shell. It prompts for each secret rather than taking them as
# arguments, so nothing lands in your shell history or in the process list
# where another user on the box could read it with `ps`.
#
#   bash scripts/set-razorpay-config.sh
#
set -euo pipefail

APP_NAME="${APP_NAME:-bitesite-app}"
SITE_URL="${SITE_URL:-https://app.bitesite.in}"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
warn() { printf '\033[33m%s\033[0m\n' "$1"; }
fail() { printf '\033[31m%s\033[0m\n' "$1" >&2; exit 1; }

command -v az >/dev/null || fail "Azure CLI not found. Install it, or run this in the Azure Cloud Shell."
az account show >/dev/null 2>&1 || fail "Not logged in. Run: az login"

bold "==> Locating the web app"
# Default to the known group and only fall back to a subscription-wide search if it is
# wrong. `az webapp list` enumerates every site in the subscription and can stall for
# tens of seconds behind a token refresh, which looked like the script had hung.
RESOURCE_GROUP="${RESOURCE_GROUP:-bitesite-rg}"
if ! az webapp show --name "$APP_NAME" --resource-group "$RESOURCE_GROUP" -o none 2>/dev/null; then
  echo "    not in '$RESOURCE_GROUP' — searching the subscription (this can take 30s)…"
  RESOURCE_GROUP="$(az webapp list --query "[?name=='$APP_NAME'].resourceGroup | [0]" -o tsv)"
fi
[ -n "$RESOURCE_GROUP" ] || fail "Could not find a web app named '$APP_NAME'. Set APP_NAME/RESOURCE_GROUP and retry."
echo "    app:            $APP_NAME"
echo "    resource group: $RESOURCE_GROUP"
echo "    subscription:   $(az account show --query name -o tsv)"

echo
bold "==> Razorpay credentials"
echo "    Dashboard > Account & Settings > API Keys for the first two."
echo "    Nothing you type is echoed."
echo

# Anything typed while the Azure lookup was running is still sitting in the terminal
# buffer and would otherwise be swallowed by the first prompt — an arrow key becomes an
# escape sequence in the middle of your key id, and the script rejects it as malformed.
while read -r -t 0; do read -r -n 10000 -s _discard || break; done 2>/dev/null || true

read -r  -p "  RAZORPAY_KEY_ID (rzp_live_… or rzp_test_…): " KEY_ID
read -rs -p "  RAZORPAY_KEY_SECRET: " KEY_SECRET; echo
read -rs -p "  RAZORPAY_WEBHOOK_SECRET: " WEBHOOK_SECRET; echo

[ -n "$KEY_ID" ]         || fail "Key id cannot be empty."
[ -n "$KEY_SECRET" ]     || fail "Key secret cannot be empty."
[ -n "$WEBHOOK_SECRET" ] || fail "Webhook secret cannot be empty — payment.captured webhooks are rejected without it."

case "$KEY_ID" in
  rzp_live_*) MODE="LIVE"; warn "  This is a LIVE key. Real money will move." ;;
  rzp_test_*) MODE="test"; echo   "  Test-mode key — no real charges." ;;
  *) fail "Key id should start with rzp_live_ or rzp_test_. Got: ${KEY_ID:0:9}…" ;;
esac

# Catches the classic paste error: the secret is not the key id.
[ "$KEY_SECRET" != "$KEY_ID" ] || fail "Key secret is identical to the key id — check what you pasted."

# ---------------------------------------------------------------------------
# Pre-flight: prove the credentials work BEFORE writing them.
#
# Without this the first time anyone learns a secret was mistyped is a customer
# failing to pay. Razorpay's orders endpoint accepts HTTP basic auth with the
# key id as the username and the secret as the password, so one authenticated
# GET is enough to tell a good pair from a bad one. Nothing is created.
# ---------------------------------------------------------------------------
echo
bold "==> Checking the credentials against Razorpay"
HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 \
  -u "$KEY_ID:$KEY_SECRET" \
  "https://api.razorpay.com/v1/orders?count=1" || echo "000")

case "$HTTP_CODE" in
  200) echo "    accepted by Razorpay (HTTP 200) — key id and secret match." ;;
  401) fail "Razorpay rejected these credentials (HTTP 401). The key id and secret do not match, or the key is disabled. Nothing was changed." ;;
  000) warn "    could not reach Razorpay to verify (network/timeout). Continuing without the check." ;;
  *)   warn "    unexpected response from Razorpay (HTTP $HTTP_CODE). Continuing, but verify manually." ;;
esac

# The webhook secret cannot be verified this way — it is not an API credential,
# it is a shared string you also paste into the dashboard. Getting it wrong
# means captured payments are rejected with a 400 and orders never mark paid,
# so it is worth re-reading before you continue.
echo
echo "    Webhook secret entered: ${#WEBHOOK_SECRET} characters."
echo "    It cannot be verified from here — it must match the dashboard exactly."

echo
warn "==> Applying these settings RESTARTS $APP_NAME (expect ~30-60s of downtime)."
read -r -p "    Continue? [y/N] " CONFIRM
[ "$CONFIRM" = "y" ] || [ "$CONFIRM" = "Y" ] || fail "Aborted. Nothing changed."

echo
bold "==> Setting app settings"
# One call, so the app restarts once rather than three times.
az webapp config appsettings set \
  --name "$APP_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --settings \
      RAZORPAY_KEY_ID="$KEY_ID" \
      RAZORPAY_KEY_SECRET="$KEY_SECRET" \
      RAZORPAY_WEBHOOK_SECRET="$WEBHOOK_SECRET" \
  --output none

echo "    done — 3 settings applied, app restarting."

echo
bold "==> Waiting for it to come back"
for i in $(seq 1 30); do
  if curl -fsS --max-time 10 "$SITE_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    echo "    healthy after ~$((i * 5))s"
    break
  fi
  sleep 5
  [ "$i" = "30" ] && warn "    still not healthy after 150s — check: az webapp log tail -n $APP_NAME -g $RESOURCE_GROUP"
done

cat <<EOF

$(bold "==> Next: register the webhook in Razorpay")

  Dashboard > Account & Settings > Webhooks > Add New Webhook

    URL          $SITE_URL/api/payments/webhook
    Secret       the RAZORPAY_WEBHOOK_SECRET you just entered
    Active event payment.captured        <- the only event this app handles

  The endpoint is deliberately CSRF-exempt and public; it authenticates the
  request by HMAC-verifying the body against the X-Razorpay-Signature header,
  and rejects anything that fails, so an unsigned POST does nothing.

$(bold "==> Confirm it took")

  /actuator/health hides its details from anonymous callers, so sign in to the
  admin portal and open it there. You want:

      "payments": { "ready": true,
                    "apiKeys": "configured",
                    "webhookSecret": "configured",
                    "mode": "$MODE" }

  Then place one real order end to end before you tell anyone it is open.
EOF
