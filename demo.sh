#!/usr/bin/env bash
# One-command launcher for presenting BiteSite: starts MySQL if needed, builds and
# runs the packaged jar (faster and more reliable than `mvn spring-boot:run` for a
# demo — no devtools/file-watcher involved), waits until it's actually healthy, then
# prints the URL and every demo account so it's all on screen before you present.
#
# Usage:  ./demo.sh
# Stop:   Ctrl+C in this terminal (cleans up the app process automatically)

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

JAVA21_HOME="$(brew --prefix openjdk@21 2>/dev/null)/libexec/openjdk.jdk/Contents/Home"
if [ ! -d "$JAVA21_HOME" ]; then
    echo "JDK 21 not found via Homebrew. Install it first:" >&2
    echo "  brew install openjdk@21" >&2
    exit 1
fi
export JAVA_HOME="$JAVA21_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

# Seed data (demo accounts, sample menu) lives outside the default migration path so a
# plain production deploy never gets it (see application.yml) — this script opts back in
# so the demo experience is unchanged.
export FLYWAY_LOCATIONS="classpath:db/migration,classpath:db/seed"

# Self-generated VAPID key pair (not a vendor secret, unlike Razorpay/Twilio/SMTP above) so
# push notifications actually work in the local demo. Generate your own for a real deploy —
# see README — this pair is for local dev only.
export VAPID_PUBLIC_KEY="BIL8Vifygn-oR4h_Kw1PFmVTAeHil6Su-U8KLh3U1bBLONl_bOsVgh46WDPMBs0lJo8L4BqTYB1HSBPpxz-1Q0k="
export VAPID_PRIVATE_KEY="H4V6rMaKBTkVB0vqUgprA-VTz3M59TkFKMCwYuWxfqE="

LOG_FILE="/tmp/bitesite-demo.log"
APP_PID=""
CLEANED_UP=false

cleanup() {
    [ "$CLEANED_UP" = true ] && return
    CLEANED_UP=true
    echo ""
    if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
        echo "==> Stopping BiteSite..."
        kill "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
    STILL_ON_8080="$(lsof -ti:8080 2>/dev/null || true)"
    if [ -n "$STILL_ON_8080" ]; then
        kill "$STILL_ON_8080" 2>/dev/null || true
    fi
    echo "Stopped."
}
trap cleanup EXIT INT TERM

echo "==> Starting MySQL..."
brew services start mysql >/dev/null 2>&1 || true

echo "==> Waiting for MySQL to accept connections..."
MYSQL_UP=false
for _ in $(seq 1 30); do
    if mysql -u root -e "SELECT 1" >/dev/null 2>&1; then
        MYSQL_UP=true
        break
    fi
    sleep 1
done
if [ "$MYSQL_UP" != true ]; then
    echo "MySQL isn't responding. Is it installed? brew install mysql" >&2
    exit 1
fi

mysql -u root -e "CREATE DATABASE IF NOT EXISTS bitesite_db CHARACTER SET utf8mb4;"

EXISTING_PID="$(lsof -ti:8080 2>/dev/null || true)"
if [ -n "$EXISTING_PID" ]; then
    echo "==> Port 8080 is already in use — stopping it first..."
    kill "$EXISTING_PID" 2>/dev/null || true
    sleep 2
fi

echo "==> Building BiteSite (skipping tests for a faster start)..."
mvn -q clean package -DskipTests

echo "==> Starting the app..."
nohup java -jar target/bitesite.jar > "$LOG_FILE" 2>&1 &
APP_PID=$!

echo "==> Waiting for it to come up..."
APP_UP=false
for _ in $(seq 1 60); do
    if curl -s http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        APP_UP=true
        break
    fi
    sleep 1
done

if [ "$APP_UP" != true ]; then
    echo ""
    echo "The app didn't come up in time. Check the log:"
    echo "  tail -f $LOG_FILE"
    exit 1
fi

command -v open >/dev/null 2>&1 && open "http://localhost:8080/login" >/dev/null 2>&1 || true

clear 2>/dev/null || true
cat <<'BANNER'
========================================================================
  BiteSite is running:  http://localhost:8080/login
========================================================================

  Every account below shares the password:   Demo@12345

  Role            Email                     College
  --------------  ------------------------  ----------------
  Super Admin     admin@bitesite.local      (platform-wide)
  Tech Manager    tech@bitesite.local       (platform-wide)
  Canteen Staff   canteen@demo.local        Demo College
  Student         student@demo.local        Demo College
  Canteen Staff   canteen@second.local      Second College
  Student         student@second.local      Second College

  Suggested demo flow:
    1. student@demo.local    -> browse menu, add to cart, checkout
                                 (payment isn't configured with live
                                 credentials, so checkout will show a
                                 clear "not configured" message instead
                                 of actually charging anything)
    2. canteen@demo.local    -> live order queue for that outlet
    3. admin@bitesite.local  -> onboard a college, view the audit log
    4. student@second.local  -> same app, completely different
                                 college's menu/data — proves
                                 multi-tenancy isolation

  Press Ctrl+C here to stop the app when you're done presenting.
  Logs while running: tail -f /tmp/bitesite-demo.log
========================================================================
BANNER

wait "$APP_PID"
