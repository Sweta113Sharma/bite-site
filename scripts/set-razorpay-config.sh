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
RESOURCE_GROUP="${RESOURCE_GROUP:-$(az webapp list --query "[?name=='$APP_NAME'].resourceGroup | [0]" -o tsv)}"
[ -n "$RESOURCE_GROUP" ] || fail "Could not find a web app named '$APP_NAME'. Set APP_NAME/RESOURCE_GROUP and retry."
echo "    app:            $APP_NAME"
echo "    resource group: $RESOURCE_GROUP"
echo "    subscription:   $(az account show --query name -o tsv)"

echo
bold "==> Razorpay credentials"
echo "    Dashboard > Account & Settings > API Keys for the first two."
echo "    Nothing you type is echoed."
echo

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
