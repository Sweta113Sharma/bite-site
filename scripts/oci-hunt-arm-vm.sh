#!/usr/bin/env bash
#
# Hunts for Ampere A1 capacity in Oracle Cloud and launches an instance the moment
# some appears.
#
# WHY THIS EXISTS
# ---------------
# Oracle's Always Free A1 shapes are heavily oversubscribed. A launch does not queue;
# it fails immediately with "Out of host capacity", and the only way through is to keep
# asking. Capacity is released continuously as other tenants tear instances down, so a
# patient retry usually wins where a single attempt never does.
#
# WHAT IT DOES DIFFERENTLY FROM A NAIVE LOOP
# ------------------------------------------
#   * Asks for the SMALLEST useful shape first. A 1 OCPU / 6 GB request is satisfiable
#     from a fragmented host that cannot fit 2 OCPU / 12 GB, and a running small
#     instance can be scaled up in place later. Getting a foot in the door beats
#     holding out for the whole allowance.
#   * Distinguishes "no capacity" (retry, expected, quiet) from a real error (stop and
#     say so). A loop that treats a bad subnet id as "out of capacity" retries forever
#     against a mistake.
#   * Backs off with jitter. Every free-tier hunter on the internet polls on the minute;
#     jitter avoids arriving in the same thundering herd, and backoff avoids the API
#     rate limits that would lock us out entirely.
#   * Cycles through every fault domain, plus unspecified placement. One AD in this
#     region, but three fault domains, and capacity is per fault domain.
#   * Stops the instant it succeeds, and prints how to reach the machine.
#
# USAGE
#   ./oci-hunt-arm-vm.sh                 # hunt with defaults, forever
#   OCPUS=2 MEM=12 ./oci-hunt-arm-vm.sh  # ask for the whole allowance instead
#   MAX_ATTEMPTS=100 ./oci-hunt-arm-vm.sh
#
set -uo pipefail

PROFILE="${OCI_PROFILE:-default}"
OCPUS="${OCPUS:-1}"
MEM="${MEM:-6}"
NAME="${NAME:-bitesite-arm}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/oci_arm_key.pub}"
MIN_SLEEP="${MIN_SLEEP:-90}"
MAX_SLEEP="${MAX_SLEEP:-300}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-0}"          # 0 = forever
LOG="${LOG:-$HOME/oci-arm-hunt.log}"

say() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG"; }

# Which kind of credential this profile holds, worked out rather than asked for. A
# profile with a user= line is an API key; one without is a browser session and needs
# --auth security_token. Passing the wrong one fails as "credentials not usable", which
# reads like an expired login and sends you round the authenticate loop for nothing.
AUTH=""
if [ -n "${OCI_AUTH+set}" ]; then
    AUTH="$OCI_AUTH"
elif ! awk -v p="[$PROFILE]" '$0==p{f=1;next} /^\[/{f=0} f&&/^ *user *=/{found=1} END{exit !found}' ~/.oci/config; then
    AUTH="--auth security_token"
fi

# Silences the CLI's nag about the key file lacking an OCI_API_KEY trailer, which would
# otherwise prefix every one of thousands of log lines during a long hunt.
export SUPPRESS_LABEL_WARNING=True
# stdin is closed on every call. When a session token expires the CLI asks
# "re-authenticate? [Y/n]" and blocks forever waiting for an answer nobody is there to
# give — which is how an unattended hunt silently stops hunting.
oci_() { oci --profile "$PROFILE" $AUTH "$@" < /dev/null; }

# ---- preflight ------------------------------------------------------------
[ -f "$SSH_KEY" ] || { say "FATAL: no ssh public key at $SSH_KEY"; exit 1; }

# Handles both "key=value" and "key = value"; the CLI writes the first, humans the second.
TENANCY=$(awk -v p="[$PROFILE]" '$0==p{f=1;next} /^\[/{f=0} f&&/^ *tenancy *=/{sub(/^[^=]*= */,""); gsub(/ /,""); print; exit}' ~/.oci/config)
[ -n "$TENANCY" ] || { say "FATAL: no tenancy in ~/.oci/config profile [$PROFILE]"; exit 1; }

say "checking credentials"
if ! oci_ iam region-subscription list >/dev/null 2>&1; then
    say "FATAL: credentials are not usable. If this is a session profile it has expired:"
    say "       oci session authenticate --region ap-hyderabad-1"
    say "       A session lasts about an hour, which is why an API key is better for a long hunt."
    exit 1
fi

say "resolving availability domain, subnet and image"
AD=$(oci_ iam availability-domain list --compartment-id "$TENANCY" \
        --query 'data[0].name' --raw-output 2>/dev/null)
SUBNET=$(oci_ network subnet list --compartment-id "$TENANCY" \
        --query 'data[0].id' --raw-output 2>/dev/null)
# Newest Canonical Ubuntu build that the A1 shape accepts. Filtering by shape is what
# keeps an x86-only image out of the list; an aarch64 mismatch fails at launch with a
# message that looks nothing like a capacity problem.
IMAGE=$(oci_ compute image list --compartment-id "$TENANCY" \
        --shape VM.Standard.A1.Flex --operating-system "Canonical Ubuntu" \
        --sort-by TIMECREATED --limit 1 --query 'data[0].id' --raw-output 2>/dev/null)

for v in AD SUBNET IMAGE; do
    [ -n "${!v}" ] && [ "${!v}" != "null" ] || { say "FATAL: could not resolve $v"; exit 1; }
done
# ap-hyderabad-1 has exactly one availability domain, so there is no AD to rotate
# through — but that one AD has three FAULT domains, and capacity is tracked per fault
# domain. Asking without naming one lets Oracle place it, and Oracle placing it is not
# the same as Oracle trying all three. The hunt cycles through "unspecified" plus each
# fault domain by name, so a host with room in FD-3 is not missed for the whole night
# because the default placement kept landing in FD-1.
#
# This does NOT increase the request rate: it varies WHERE each attempt asks for, not
# how often it asks. The rate limiter counts calls, and the count is unchanged.
# Built with a read loop rather than mapfile: macOS ships bash 3.2, where mapfile does
# not exist, and the failure is silent — an empty array, no rotation, and a hunt that
# looks like it is covering three fault domains while only ever asking for one.
PLACEMENTS=("")
FD_COUNT=0
while IFS= read -r fd; do
    [ -n "$fd" ] || continue
    PLACEMENTS+=("$fd")
    FD_COUNT=$((FD_COUNT + 1))
done < <(oci_ iam fault-domain list --compartment-id "$TENANCY" \
        --availability-domain "$AD" --query 'data[].name' --raw-output 2>/dev/null \
        | grep -oE 'FAULT-DOMAIN-[0-9]+')

say "AD=$AD"
say "fault domains=$FD_COUNT (cycling ${#PLACEMENTS[@]} placements incl. unspecified)"
say "subnet=${SUBNET: -12}  image=${IMAGE: -12}"
say "hunting for ${OCPUS} OCPU / ${MEM} GB as '$NAME' — Ctrl-C to stop"

# ---- the hunt -------------------------------------------------------------
attempt=0
throttled=0
timeouts=0
while :; do
    attempt=$((attempt + 1))
    [ "$MAX_ATTEMPTS" -gt 0 ] && [ "$attempt" -gt "$MAX_ATTEMPTS" ] && {
        say "gave up after $MAX_ATTEMPTS attempts"; exit 2; }

    # Rotate placement per attempt. Index cycles 0..n-1 across the placement list.
    FD="${PLACEMENTS[$(( (attempt - 1) % ${#PLACEMENTS[@]} ))]}"
    # Expanded with the ${arr[@]+...} guard: this script runs under `set -u`, and bash
    # 3.2 (which is what macOS ships) treats "${empty[@]}" as an unbound variable and
    # aborts. That is not theoretical — it killed this hunt on attempt 1.
    FD_ARGS=()
    [ -n "$FD" ] && FD_ARGS=(--fault-domain "$FD")

    out=$(oci_ compute instance launch \
            --compartment-id "$TENANCY" \
            --availability-domain "$AD" \
            ${FD_ARGS[@]+"${FD_ARGS[@]}"} \
            --shape VM.Standard.A1.Flex \
            --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEM}" \
            --subnet-id "$SUBNET" \
            --image-id "$IMAGE" \
            --display-name "$NAME" \
            --assign-public-ip true \
            --metadata "{\"ssh_authorized_keys\":\"$(cat "$SSH_KEY")\"}" \
            --wait-for-state RUNNING 2>&1)
    rc=$?

    if [ $rc -eq 0 ]; then
        say "GOT ONE after $attempt attempts ($throttled rate-limited)"
        id=$(printf '%s' "$out" | grep -oE '"id": "ocid1\.instance[^"]*"' | head -1 | cut -d'"' -f4)
        ip=$(oci_ compute instance list-vnics --instance-id "$id" \
                --query 'data[0]."public-ip"' --raw-output 2>/dev/null)
        say "instance=$id"
        say "public ip=$ip"
        say "ssh -i ${SSH_KEY%.pub} ubuntu@$ip"
        exit 0
    fi

    # "Out of capacity" is the expected answer and means keep going. Anything else is a
    # mistake on our side and retrying it forever would just hide it.
    if printf '%s' "$out" | grep -qiE "out of (host )?capacity|OutOfCapacity|too busy"; then
        # Oracle answered, so whatever the network was doing, it is over.
        timeouts=0
        sleep_for=$(( MIN_SLEEP + RANDOM % (MAX_SLEEP - MIN_SLEEP + 1) ))
        printf '%s  attempt %-5d no capacity in %-16s retrying in %ss\n' \
            "$(date '+%H:%M:%S')" "$attempt" "${FD:-<unspecified>}" "$sleep_for" | tee -a "$LOG"
        sleep "$sleep_for"
        continue
    fi

    # 429 is not contention and not a mistake — it is us asking too often. Oracle
    # throttles launch_instance aggressively, and a hunter that treats this like a
    # capacity miss just digs in deeper and spends the night being refused for the
    # wrong reason. Stand well back, then carry on.
    if printf '%s' "$out" | grep -qiE "TooManyRequests|429"; then
        throttled=$((throttled + 1))
        cool=$(( 600 + RANDOM % 600 ))
        say "attempt $attempt rate-limited (429) — cooling off for $((cool / 60))m"
        sleep "$cool"
        continue
    fi

    # A dropped connection says nothing about capacity and nothing about us. A laptop
    # that slept, a wifi handover, an Oracle endpoint having a moment — treating any of
    # those as fatal ends an overnight hunt at 3am for no reason. Retry, but count them:
    # a run where every attempt times out is a broken network, not a hunt.
    if printf '%s' "$out" | grep -qiE "connection to endpoint timed out|ConnectTimeout|ReadTimeout|Max retries exceeded|Temporary failure in name resolution|Could not connect"; then
        timeouts=$((timeouts + 1))
        if [ "$timeouts" -ge 12 ]; then
            say "STOPPED: $timeouts network timeouts and no successful call — check the connection."
            exit 6
        fi
        cool=$(( 60 + RANDOM % 120 ))
        say "attempt $attempt could not reach Oracle (network), retrying in ${cool}s"
        sleep "$cool"
        continue
    fi

    if printf '%s' "$out" | grep -qiE "NotAuthenticated|session has expired"; then
        say "STOPPED: credentials expired after $attempt attempts."
        say "  Re-authenticate and restart:  oci session authenticate --region ap-hyderabad-1"
        exit 3
    fi

    if printf '%s' "$out" | grep -qiE "LimitExceeded|service limit"; then
        say "STOPPED: service limit reached — this tenancy will not give more A1 than it already has."
        printf '%s\n' "$out" | tail -5 | tee -a "$LOG"
        exit 4
    fi

    say "STOPPED on an unexpected error after $attempt attempts:"
    printf '%s\n' "$out" | tail -20 | tee -a "$LOG"
    exit 5
done
