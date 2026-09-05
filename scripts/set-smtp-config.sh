#!/usr/bin/env bash
#
# Sets the SMTP credentials on the BiteSite Azure Web App, for Brevo or any other
# STARTTLS relay.
#
# Until these are set, UserService.registerStudent creates every account already
# marked verified — nobody proves they own the address they signed up with. That is
# what this closes.
#
#   bash scripts/set-smtp-config.sh
#
# It prompts rather than taking arguments, so nothing lands in your shell history or
# in the process list. It also sends a real test email before writing anything: an
# SMTP login can succeed and the send still be rejected, because relays only accept
# a From address you have verified with them. Logging in proves the password;
# delivering proves the setup.
#
set -euo pipefail

APP_NAME="${APP_NAME:-bitesite-app}"
RESOURCE_GROUP="${RESOURCE_GROUP:-bitesite-rg}"
SITE_URL="${SITE_URL:-https://app.bitesite.in}"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
warn() { printf '\033[33m%s\033[0m\n' "$1"; }
fail() { printf '\033[31m%s\033[0m\n' "$1" >&2; exit 1; }

command -v az       >/dev/null || fail "Azure CLI not found. Install it, or use the Azure Cloud Shell."
command -v python3  >/dev/null || fail "python3 not found — needed for the connection test."
az account show >/dev/null 2>&1 || fail "Not logged in. Run: az login"

bold "==> Locating the web app"
az webapp show --name "$APP_NAME" --resource-group "$RESOURCE_GROUP" -o none 2>/dev/null \
  || fail "No web app '$APP_NAME' in '$RESOURCE_GROUP'. Set APP_NAME/RESOURCE_GROUP and retry."
echo "    app:            $APP_NAME"
echo "    resource group: $RESOURCE_GROUP"

cat <<'EOF'

  Where these come from in Brevo:

    Host / Port   smtp-relay.brevo.com : 587        (defaults below)
    Username      SMTP & API > SMTP tab > "Login"
    Password      the SMTP KEY on that same page
                  NOT your Brevo account password, and NOT a v3 API key
    From address  Senders, Domains & Dedicated IPs > Senders
                  It must be verified there or Brevo rejects every send.

EOF

# Discard anything typed while the Azure lookup ran, so a stray keypress cannot end up
# inside the first answer.
while read -r -t 0; do read -r -n 10000 -s _discard || break; done 2>/dev/null || true

read -r  -p "  SMTP_HOST [smtp-relay.brevo.com]: " SMTP_HOST
SMTP_HOST="${SMTP_HOST:-smtp-relay.brevo.com}"
read -r  -p "  SMTP_PORT [587]: " SMTP_PORT
SMTP_PORT="${SMTP_PORT:-587}"
read -r  -p "  SMTP_USERNAME: " SMTP_USERNAME
read -rs -p "  SMTP_PASSWORD (not echoed): " SMTP_PASSWORD; echo
read -r  -p "  MAIL_FROM (a VERIFIED sender): " MAIL_FROM
read -r  -p "  Send a test message to: " TEST_TO

[ -n "$SMTP_USERNAME" ] || fail "Username cannot be empty."
[ -n "$SMTP_PASSWORD" ] || fail "Password cannot be empty."
[ -n "$MAIL_FROM" ]     || fail "From address cannot be empty."
[ -n "$TEST_TO" ]       || fail "Test recipient cannot be empty."

case "$MAIL_FROM" in
  *@bitesite.local) fail "bitesite.local is the dev placeholder, not a real domain. Use a sender you have verified with Brevo." ;;
  *@*.*) ;;
  *) fail "MAIL_FROM does not look like an email address: $MAIL_FROM" ;;
esac

echo
bold "==> Testing the relay"
echo "    connect -> STARTTLS -> authenticate -> send one message"

SMTP_HOST="$SMTP_HOST" SMTP_PORT="$SMTP_PORT" SMTP_USERNAME="$SMTP_USERNAME" \
SMTP_PASSWORD="$SMTP_PASSWORD" MAIL_FROM="$MAIL_FROM" TEST_TO="$TEST_TO" \
python3 - <<'PYEOF' || fail "The relay rejected this configuration. Nothing was changed."
import os, smtplib, ssl, sys
from email.message import EmailMessage

host, port = os.environ["SMTP_HOST"], int(os.environ["SMTP_PORT"])
user, pwd  = os.environ["SMTP_USERNAME"], os.environ["SMTP_PASSWORD"]
sender, to = os.environ["MAIL_FROM"], os.environ["TEST_TO"]

try:
    with smtplib.SMTP(host, port, timeout=25) as s:
        s.ehlo()
        s.starttls(context=ssl.create_default_context())
        s.ehlo()
        print("    TLS negotiated")
        s.login(user, pwd)
        print("    authenticated as", user)

        msg = EmailMessage()
        msg["Subject"] = "BiteSite SMTP test"
        msg["From"], msg["To"] = sender, to
        msg.set_content(
            "If you are reading this, BiteSite can send mail.\n\n"
            "That means student verification codes will actually be delivered, and new\n"
            "accounts stop being created pre-verified.\n")
        s.send_message(msg)
        print("    message accepted for delivery to", to)
except smtplib.SMTPAuthenticationError:
    print("    AUTH FAILED — check the SMTP key. Brevo wants the SMTP key, not your", file=sys.stderr)
    print("    account password and not a v3 API key.", file=sys.stderr)
    sys.exit(1)
except smtplib.SMTPSenderRefused as e:
    print(f"    SENDER REFUSED — {sender} is not a verified sender on this account.", file=sys.stderr)
    print(f"    Verify it under Senders, Domains & Dedicated IPs. ({e})", file=sys.stderr)
    sys.exit(1)
except Exception as e:
    print(f"    FAILED — {type(e).__name__}: {e}", file=sys.stderr)
    sys.exit(1)
PYEOF

echo
warn "==> Check that the test message actually arrived at $TEST_TO before continuing."
warn "    Look in spam too — a brand new sending domain often lands there first."
read -r -p "    Did it arrive? [y/N] " GOT_IT
[ "$GOT_IT" = "y" ] || [ "$GOT_IT" = "Y" ] || fail "Aborted — nothing changed. Fix delivery first; an accepted message that never lands is the same as no email at all."

echo
warn "==> Applying these settings RESTARTS $APP_NAME (~40s of downtime)."
warn "    You are taking live payments — pick a quiet moment."
read -r -p "    Continue? [y/N] " CONFIRM
[ "$CONFIRM" = "y" ] || [ "$CONFIRM" = "Y" ] || fail "Aborted. Nothing changed."

echo
bold "==> Setting app settings"
# One call, so the app restarts once rather than six times. MAIL_HEALTH_ENABLED turns on
# Boot's mail indicator, which is off by default because it would report DOWN whenever
# SMTP is unconfigured — now that it is configured, a broken relay should be visible in
# /actuator/health rather than as students who never get their code.
az webapp config appsettings set \
  --name "$APP_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --settings \
      SMTP_HOST="$SMTP_HOST" \
      SMTP_PORT="$SMTP_PORT" \
      SMTP_USERNAME="$SMTP_USERNAME" \
      SMTP_PASSWORD="$SMTP_PASSWORD" \
      MAIL_FROM="$MAIL_FROM" \
      MAIL_HEALTH_ENABLED=true \
  --output none

echo "    done — 6 settings applied, app restarting."

echo
bold "==> Waiting for it to come back"
for i in $(seq 1 40); do
  if curl -fsS --max-time 10 "$SITE_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    echo "    healthy after ~$((i * 5))s"
    break
  fi
  sleep 5
  [ "$i" = "40" ] && warn "    not healthy after 200s — check: az webapp log tail -n $APP_NAME -g $RESOURCE_GROUP"
done

cat <<EOF

$(bold "==> What changed")

  New students now receive a verification code and must enter it. Accounts are no
  longer created pre-verified, so an address has to be provable to be usable.

$(bold "==> Confirm it end to end")

  1. Register a NEW student at $SITE_URL/register/student with an address you can read.
  2. The code should arrive within a minute. Enter it.
  3. Existing accounts are unaffected — they were already marked verified.

  If nothing arrives, the relay accepted it but delivery failed downstream. Brevo's
  Statistics > Email > Logs shows what happened to each message.
EOF
