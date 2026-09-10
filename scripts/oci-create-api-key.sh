#!/usr/bin/env bash
#
# Turns a browser session into a durable API key, so unattended jobs keep working.
#
# WHY
# ---
# An OCI session token lasts about an hour. That is fine for a person at a keyboard and
# useless for a capacity hunt that may run overnight: the CLI stops, asks "re-authenticate?",
# and waits for an answer nobody is there to give. An API key does not expire.
#
# WHAT IT DOES
#   1. Confirms the browser session currently works (it is the thing being traded in).
#   2. Generates a fresh 2048-bit RSA key pair under ~/.oci, private key mode 600.
#   3. Registers the public half against your user via the session.
#   4. Writes a SEPARATE profile so nothing existing is clobbered.
#   5. Proves the new profile works before telling you it does.
#
# The private key never leaves this machine and is never printed.
#
# USAGE
#   oci session authenticate --region ap-hyderabad-1     # first, in a browser
#   ./oci-create-api-key.sh
#
set -uo pipefail

SESSION_PROFILE="${SESSION_PROFILE:-default}"
NEW_PROFILE="${NEW_PROFILE:-bitesite}"
KEY_DIR="${KEY_DIR:-$HOME/.oci}"
KEY_BASE="$KEY_DIR/${NEW_PROFILE}_api_key"
CONFIG="$HOME/.oci/config"

say() { printf '  %s\n' "$*"; }
fail() { printf '  FATAL: %s\n' "$*"; exit 1; }

grep -q "^\[$NEW_PROFILE\]" "$CONFIG" 2>/dev/null && \
    fail "profile [$NEW_PROFILE] already exists in $CONFIG — remove it or set NEW_PROFILE=something-else"

say "1/5  checking the browser session"
oci iam region-subscription list --profile "$SESSION_PROFILE" --auth security_token \
    >/dev/null 2>&1 < /dev/null \
    || fail "session profile [$SESSION_PROFILE] is not usable. Run: oci session authenticate --region ap-hyderabad-1"

TENANCY=$(awk -v p="[$SESSION_PROFILE]" '$0==p{f=1;next} /^\[/{f=0} f&&/^ *tenancy *=/{sub(/^[^=]*= */,""); gsub(/ /,""); print; exit}' "$CONFIG")
REGION=$(awk -v p="[$SESSION_PROFILE]" '$0==p{f=1;next} /^\[/{f=0} f&&/^ *region *=/{sub(/^[^=]*= */,""); gsub(/ /,""); print; exit}' "$CONFIG")
[ -n "$TENANCY" ] || fail "no tenancy found in [$SESSION_PROFILE]"
[ -n "$REGION" ] || fail "no region found in [$SESSION_PROFILE]"

# The session knows who it belongs to; ask it rather than making anyone paste an OCID.
say "2/5  resolving your user"
USER_OCID=$(oci iam user list --profile "$SESSION_PROFILE" --auth security_token \
    --compartment-id "$TENANCY" --query 'data[0].id' --raw-output 2>/dev/null < /dev/null)
[ -n "$USER_OCID" ] && [ "$USER_OCID" != "null" ] || \
    fail "could not resolve the user OCID from the session"
say "     user ...${USER_OCID: -12}"

say "3/5  generating a key pair"
mkdir -p "$KEY_DIR" && chmod 700 "$KEY_DIR"
openssl genrsa -out "$KEY_BASE.pem" 2048 2>/dev/null || fail "key generation failed"
chmod 600 "$KEY_BASE.pem"
openssl rsa -pubout -in "$KEY_BASE.pem" -out "$KEY_BASE.pub" 2>/dev/null || fail "public key extraction failed"
FINGERPRINT=$(openssl rsa -pubout -outform DER -in "$KEY_BASE.pem" 2>/dev/null \
    | openssl md5 -c | sed 's/.*= //')
say "     fingerprint ${FINGERPRINT:0:11}…"

say "4/5  registering the public key on your user"
UPLOAD=$(oci iam user api-key upload --profile "$SESSION_PROFILE" --auth security_token \
    --user-id "$USER_OCID" --key-file "$KEY_BASE.pub" 2>&1 < /dev/null)
if [ $? -ne 0 ]; then
    printf '%s\n' "$UPLOAD" | tail -6 | sed 's/^/     /'
    # Three keys per user is the cap, and it is the usual reason this step fails.
    printf '%s' "$UPLOAD" | grep -qi "limit" && \
        say "     (a user may hold at most 3 API keys — delete an unused one in the console)"
    rm -f "$KEY_BASE.pem" "$KEY_BASE.pub"
    fail "could not register the key; nothing was left behind"
fi

cat >> "$CONFIG" <<EOF

[$NEW_PROFILE]
user=$USER_OCID
fingerprint=$FINGERPRINT
key_file=$KEY_BASE.pem
tenancy=$TENANCY
region=$REGION
EOF

say "5/5  verifying the new profile"
# Freshly uploaded keys take a moment to propagate; a single immediate check is a false negative.
for i in 1 2 3 4 5 6; do
    if oci iam region-subscription list --profile "$NEW_PROFILE" >/dev/null 2>&1 < /dev/null; then
        say ""
        say "Done. Profile [$NEW_PROFILE] works and does not expire."
        say "Hunt with:  OCI_PROFILE=$NEW_PROFILE OCI_AUTH= ./scripts/oci-hunt-arm-vm.sh"
        exit 0
    fi
    sleep 5
done

say ""
say "The key was registered but the profile did not authenticate within 30s."
say "Keys can take a minute to propagate — try again shortly:"
say "  oci iam region-subscription list --profile $NEW_PROFILE"
exit 1
