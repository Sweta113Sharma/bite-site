# BiteSite

A multi-tenant SaaS platform for college canteen pre-ordering. Students order from class
during a break, pay online, and collect food during lunch instead of standing in a queue.
One college's students, canteen staff, and menu are never visible to another college — the
platform can onboard any number of colleges without a redeploy.

Originally an "Advanced Java" course project; rebuilt as production-oriented software per
the team's direction — see [Architecture](#architecture) for what that means concretely.

## Tech stack

- **Java 21** (LTS), **Spring Boot 3.5**, **Maven**
- **Spring MVC + Thymeleaf + Bootstrap 5** — server-rendered, no separate frontend build
- **Spring JDBC (`JdbcTemplate`)** — not JPA/Hibernate, by design
- **MySQL 8**, schema versioned with **Flyway**
- **Spring Security** — form login, BCrypt, CSRF, method security
- **Spring Session JDBC** — HTTP sessions live in MySQL, not the app's heap, so a restart or
  a second app instance behind a load balancer doesn't log everyone out
- **Razorpay** (Java SDK) for payment
- **Spring Boot Actuator** for health/metrics/build info
- **Sentry** (optional, `SENTRY_DSN`-gated) for error tracking
- **GitHub Actions** CI — build + full test suite against a real MySQL service container on
  every push/PR

## Architecture

### Multi-tenancy: account-based, not subdomain-based

Every college is a row in `tenants`. Every tenant-scoped table (`outlets`, `users`,
`menu_items`, `orders`, `order_items`, `payments`, `grievances`) carries a `tenant_id`.

**Which college's data a request sees is decided by the logged-in user's own account, not
by any URL, subdomain, or client-supplied value.** After Spring Security authenticates a
request, `TenantResolutionInterceptor` reads the principal's `tenant_id` and sets
`TenantContext` for that request. Every controller derives `tenantId` from
`principal.getUser().getTenantId()` — never from a request parameter — and every
tenant-scoped DAO method requires `tenantId` as an argument. A user from College A can never
read or write College B's data, even by guessing an internal ID (see
`TenantIsolationSecurityTest`, which proves this against a real MySQL instance through the
full HTTP → Security → controller → service → DAO stack).

Platform-level roles (`SUPER_ADMIN`, `TECH_MANAGER`) have `tenant_id = NULL` and aren't
scoped to any one college.

### Roles

| Role | Scope | Console |
|---|---|---|
| `SUPER_ADMIN` | platform | `/admin` — onboard colleges, upload logos, add canteens, create canteen staff accounts, resolve grievances, view the audit log, run the sales/onboarding pipeline |
| `TECH_MANAGER` | platform | `/techmgr` — per-college config (feature flags, etc.), system health |
| `CANTEEN_STAFF` | one college + one canteen (`outlet`) | `/canteen` — menu, prices, discounts, stock, live order queue |
| `STUDENT` | one college | `/student` — browse menu, cart, pay-gated checkout, order tracking, grievances |

A college can have more than one canteen (`outlets` table) — students pick which one
they're ordering from; each canteen has its own staff, menu, and order queue.

### Order lifecycle (payment is mandatory before the kitchen sees anything)

```
AWAITING_PAYMENT → PAID → PREPARING → READY_FOR_PICKUP → COMPLETED
                 ↘ PAYMENT_FAILED / EXPIRED / CANCELLED
```

There is deliberately no "pay at counter" path — an unpaid no-show would mean prepped food
gets thrown away. The canteen's live queue only ever shows orders at `PAID` or later.
`OrderStatus.canTransitionTo()` is the single source of truth for legal transitions;
nothing else sets order status directly.

Payment confirmation has two independent paths that converge on the same idempotent
`OrderService.confirmPayment()`: the Razorpay Checkout.js client-side callback (fast, but
not fully trustworthy on its own — the signature is verified server-side before anything is
marked paid) and a webhook (`POST /api/payments/webhook`, verified via
`X-Razorpay-Signature`) as the authoritative backstop if the browser tab closes before the
client callback fires.

### Other production concerns already in place

- **Flyway migrations** (`src/main/resources/db/migration`) instead of a hand-run schema
  script — currently at V7. Seed/demo data (`db/seed`, V2 and V8) is a **separate, opt-in**
  Flyway location — see [Seed accounts](#seed-accounts) below for why.
- **Audit log** (`audit_log` table) on price/discount changes, order status changes,
  tenant/user changes, grievance resolution, and self-service account deletion — viewable at
  `/admin/audit-log`.
- **Rate limiting**: login (10 attempts / 5 min per IP), registration (20 / 15 min per IP),
  checkout (5 attempts / min per user), grievance submission (5 / hour per user), and
  OTP resend (3 / 15 min per account+channel) — all backed by a MySQL table
  (`rate_limit_window`), not an in-memory map, so it works correctly across a restart or more
  than one app instance.
- **Payment refunds**: cancelling a paid order (`/canteen/queue/{id}/cancel`) issues a real
  Razorpay refund before the order is marked cancelled — if the refund call fails, nothing
  changes on our side, so an order can never end up cancelled while the customer stays
  charged. The old generic status-update endpoint no longer accepts `PAID` or `CANCELLED` as
  targets at all (it used to — any authenticated canteen-staff account could POST
  `newStatus=PAID` for an order that was never actually paid, or `newStatus=CANCELLED` with
  no refund; both are closed now).
- **HTTP sessions in MySQL** (`SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` tables via
  Spring Session JDBC) — same reasoning as rate limiting: survives a restart, works across
  multiple instances.
- **Global exception handling** (`GlobalExceptionHandler`): every uncaught exception
  becomes a branded error page (or a structured JSON body under `/api/**`) — never a raw
  stack trace in the response — and is reported to Sentry when `SENTRY_DSN` is set.
- **File storage** for tenant logos is behind a `FileStorageService` interface with two
  implementations: local-disk (default) and Cloudinary (`UPLOAD_STORAGE_TYPE=cloudinary` +
  three `CLOUDINARY_*` env vars). Local-disk is fine for one server; Cloudinary is the
  free-tier-friendly option for anything ephemeral or multi-instance.
- **Privacy policy, terms, and self-service account deletion** (`/privacy-policy`, `/terms`,
  `/student/account`) — DPDP 2025 groundwork. Deletion anonymizes a student's name, email,
  phone, and roll number in place rather than hard-deleting the row, so order/payment history
  the canteen legitimately needs for accounting stays intact but stops identifying anyone.
  The policy text is an honest description of what the app actually collects — **it has not
  had a legal review and shouldn't be treated as compliant until it does.**
- **Email verification** for student self-registration, gated on whether SMTP is actually
  configured (see [Email](#email-verification--transactional-mail) below) — registration and
  login behave identically to today with no SMTP set, so there's no regression from leaving
  it unconfigured.
- **Structured JSON logging**, opt-in via `LOG_FORMAT=ecs` (or `logstash`/`gelf`) — built
  into Spring Boot 3.4+, no extra dependency. Plain text by default for local dev.
- **Actuator `/info`** returns build metadata (version, build time) automatically via the
  Maven plugin's `build-info` goal, alongside static app info from `application.yml`.

## Local setup

### Prerequisites

- **JDK 21** — this matters more than it sounds. Very new JDKs (this machine's default
  Homebrew `openjdk` was version 26) break Lombok's annotation processor silently — builder
  methods, getters, etc. just don't get generated, and you get confusing "cannot find
  symbol" errors that look like real bugs. Install a real JDK 21:
  ```
  brew install openjdk@21
  export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home
  export PATH="$JAVA_HOME/bin:$PATH"
  ```
  Put the `export` lines in your shell profile so you don't have to repeat them.
- **Maven** — `brew install maven`
- **MySQL 8+** — `brew install mysql && brew services start mysql` (installs with no root
  password by default; run `mysql_secure_installation` before using this anywhere but a
  laptop)

### Database

```bash
mysql -u root -e "CREATE DATABASE bitesite_db CHARACTER SET utf8mb4;"
```

Flyway creates the schema automatically on first boot — no manual SQL needed beyond creating
the empty database. Seed/demo data is opt-in (see below) — set `FLYWAY_LOCATIONS` before
starting if you want it.

### Run it

```bash
export FLYWAY_LOCATIONS="classpath:db/migration,classpath:db/seed"   # demo accounts + sample menu
mvn spring-boot:run
```

Visit **http://localhost:8080/login**. Spring Boot DevTools is on the classpath, so Java
changes trigger an automatic restart and template/CSS changes are picked up on the next
request (after `mvn compile` if you're not using an IDE that compiles on save).

### Seed accounts

Every seeded account shares the password **`Demo@12345`**, which is checked into this repo
in plain sight — so seed data is **off by default**. `spring.flyway.locations` in
`application.yml` only points at `db/migration` (schema, no accounts) unless `FLYWAY_LOCATIONS`
is explicitly set to include `classpath:db/seed`, which is exactly what `demo.sh` and the
`mvn spring-boot:run` command above do. A plain `docker compose up` or any deploy that doesn't
set this env var gets zero seeded accounts, on a completely empty `users` table — verified by
starting the app against a fresh database with `FLYWAY_LOCATIONS` unset and confirming
`admin@bitesite.local` doesn't exist and can't log in.

Two independent demo colleges are seeded (when you opt in) so you can immediately see that
switching accounts switches which college's data you see:

| Email | Role | College |
|---|---|---|
| `admin@bitesite.local` | SUPER_ADMIN | — (platform) |
| `tech@bitesite.local` | TECH_MANAGER | — (platform) |
| `canteen@demo.local` | CANTEEN_STAFF | Demo College |
| `student@demo.local` | STUDENT | Demo College |
| `canteen@second.local` | CANTEEN_STAFF | Second College |
| `student@second.local` | STUDENT | Second College |

For a real deploy, just don't set `FLYWAY_LOCATIONS` — there's nothing else to remember.

### Payments (Razorpay)

No live Razorpay credentials are checked into this repo (there weren't any to begin with).
The integration is real and complete — Orders API, Checkout.js, signature verification, and
webhook handling — but checkout will fail with a clear "Payment is not configured yet"
message until you provide keys:

1. Create a free Razorpay account (test mode requires no business verification).
2. Set environment variables before starting the app:
   ```bash
   export RAZORPAY_KEY_ID=rzp_test_xxxxxxxx
   export RAZORPAY_KEY_SECRET=xxxxxxxxxxxxxxxx
   export RAZORPAY_WEBHOOK_SECRET=xxxxxxxxxxxxxxxx   # set when configuring the webhook below
   ```
3. Point a Razorpay webhook (Dashboard → Settings → Webhooks) at
   `https://<your-public-url>/api/payments/webhook`, subscribed to the `payment.captured`
   event, using the same secret as `RAZORPAY_WEBHOOK_SECRET`. For local testing this needs a
   tunnel (ngrok or similar) since Razorpay's servers can't reach `localhost`.

Nothing else in the app needs these keys to run — only the actual checkout flow.

### Email + phone verification (OTP)

Both are unconfigured by default (`SMTP_HOST` / `TWILIO_*` blank), and the app is built so
that's a completely normal, working state: students self-register and can log in
immediately. The moment either is configured, a self-registered student who triggers that
channel (email always; phone only if they supplied a number) gets a 6-digit code — via
`OtpService` → `SmtpEmailService`/`TwilioSmsService` — and is routed to `/verify` to enter
it before they can log in. Codes expire after 10 minutes and allow 5 wrong guesses before
they're dead (request a fresh one). Existing accounts and anything created through the admin
console are unaffected either way — this only ever gates new self-registration.

```bash
export SMTP_HOST=smtp.example.com
export SMTP_PORT=587
export SMTP_USERNAME=xxxxxxxx
export SMTP_PASSWORD=xxxxxxxx

export TWILIO_ACCOUNT_SID=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
export TWILIO_AUTH_TOKEN=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
export TWILIO_FROM_NUMBER=+1xxxxxxxxxx   # a number on your Twilio account
```

Phone numbers are assumed to be 10-digit Indian mobile numbers (`+91` is prepended before
sending) — matches the rest of this app, which is India-specific throughout.

### Push notifications (order ready / cancelled)

Blank by default (`VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` unset) — `PushNotificationService`
skips sending and the "Order notifications" toggle on the account page tells the user
they're unavailable, rather than failing. Unlike Razorpay/Twilio/SMTP, VAPID keys aren't a
vendor secret — they're a key pair this deployment generates for itself, so there's no
account to sign up for. Generate one:

```bash
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
java -cp "$(cat /tmp/cp.txt)" nl.martijndwars.webpush.cli.Cli generate-key

export VAPID_PUBLIC_KEY=<the PublicKey it prints>
export VAPID_PRIVATE_KEY=<the PrivateKey it prints>
export VAPID_SUBJECT=mailto:you@yourdomain.com   # defaults to no-reply@bitesite.local
```

`demo.sh` already exports a real (demo-only) key pair so this works out of the box locally
— generate your own for a real deploy, the same way you'd generate a fresh TLS cert rather
than reuse someone else's. A student opts in via the toggle on `/student/account`; a push
fires when their order hits READY_FOR_PICKUP or gets cancelled (`OrderService`). A dead
subscription (browser uninstalled, permission revoked) is dropped automatically on the next
send attempt rather than retried forever.

### Error tracking (Sentry)

Also blank by default (`SENTRY_DSN` unset) — the Sentry SDK checks its own DSN and simply
doesn't initialize when it's empty, so there's no conditional wiring on our side and nothing
breaks by leaving it unset. Every unhandled exception is explicitly reported via
`Sentry.captureException(e)` in `GlobalExceptionHandler` once a DSN is provided:

```bash
export SENTRY_DSN=https://xxxxxxxx@xxxxxxxx.ingest.sentry.io/xxxxxxxx
export SENTRY_ENVIRONMENT=production   # defaults to "development"
```

### Environment variables at a glance

| Variable | Default | Effect when unset | Effect when set |
|---|---|---|---|
| `RAZORPAY_KEY_ID` / `_SECRET` / `_WEBHOOK_SECRET` | blank | Checkout fails with a clear "not configured" message | Real payments |
| `SMTP_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | blank | Students self-verify implicitly, no email sent | Registration requires entering an emailed 6-digit code |
| `TWILIO_ACCOUNT_SID` / `_AUTH_TOKEN` / `_FROM_NUMBER` | blank | Phone auto-verified (or skipped if none given), no SMS sent | Registering with a phone number requires entering a texted 6-digit code |
| `VAPID_PUBLIC_KEY` / `_PRIVATE_KEY` / `_SUBJECT` | blank | "Order notifications" toggle tells the user push isn't available | Order-ready/cancelled alerts actually send |
| `UPLOAD_STORAGE_TYPE` | `local` | Logos saved to local disk | `cloudinary` switches to Cloudinary (needs the three vars below) |
| `CLOUDINARY_CLOUD_NAME` / `_API_KEY` / `_API_SECRET` | blank | Ignored (storage stays local) | Required if `UPLOAD_STORAGE_TYPE=cloudinary` |
| `SENTRY_DSN` | blank | No error reporting | Unhandled exceptions reported to Sentry |
| `LOG_FORMAT` | blank (plain text) | Human-readable console logs | `ecs`/`logstash`/`gelf` for structured JSON logs |
| `DB_URL` / `_USERNAME` / `_PASSWORD` | local root, no password | — | Point at any MySQL 8+ instance |
| `FLYWAY_LOCATIONS` | `classpath:db/migration` | No demo accounts/sample data — safe default for any real deploy | Add `,classpath:db/seed` to get the seeded demo accounts (local dev/demo only) |
| `COOKIE_SECURE` | `false` | Session cookie has no `Secure` flag — required for plain `http://localhost` dev | Set `true` in production, behind the HTTPS-terminating reverse proxy this app expects |

### HTTPS

This app does not terminate TLS itself — it expects to sit behind a reverse proxy (nginx,
Caddy, or your cloud load balancer) that does, and forwards plain HTTP to it on the internal
network. Set `COOKIE_SECURE=true` once that's in place; Spring Security's HSTS header is on
by default and only sends over an actually-HTTPS request, so it needs no extra config.

## Tests

```bash
mvn test
```

104 tests: pure unit tests for the order state machine and discount pricing, Mockito-based
service tests across every service (users, menu, tenants, orders, onboarding, grievances,
audit log, tech config, email verification), and two full-stack security test classes that
run the real Spring Security + MVC stack against a live MySQL test database:

- `TenantIsolationSecurityTest` — proves a student from one college gets a 404, not their
  data, when trying to view another college's order.
- `RolePermissionSecurityTest` — proves each of the four roles gets exactly 403 on every
  other role's console, 200 on its own, and an anonymous request gets redirected to login
  (not 403) — nine assertions across every console boundary in the app.

The security tests need their own database (kept separate from your dev data on purpose):

```bash
mysql -u root -e "CREATE DATABASE bitesite_test_db CHARACTER SET utf8mb4;"
```

CI (`.github/workflows/ci.yml`) runs the full suite against a throwaway MySQL 8.4 service
container on every push and PR, so this doesn't depend on your local database state.

## Docker

A `Dockerfile` (multi-stage: Maven+JDK21 build → JRE-only runtime, non-root user, a
`HEALTHCHECK` against `/actuator/health`) and `docker-compose.yml` (app + MySQL 8.4, restart
policies, no exposed DB port) are included. `docker-compose.yml` requires `DB_PASSWORD` to be
set explicitly — it deliberately has no working default, so compose fails loudly instead of
running with a known-weak password.

**These remain unverified — not because Docker was never attempted, but because no
environment this was built in has had a running Docker daemon available** (Docker Desktop
isn't installed; a lightweight daemon via `colima` was tried in an earlier session and hung
on VM networking the sandbox didn't allow). This is a constraint of the tooling available
while building this, not a known problem with the Dockerfile itself — but it genuinely hasn't
been run, so treat it as unverified until you build it on a machine with real Docker. Before
relying on it: build the image, confirm the app boots and Flyway migrates cleanly against the
compose MySQL service (seed data will NOT be present unless you set `FLYWAY_LOCATIONS` — see
[Seed accounts](#seed-accounts)), and confirm the `uploads` volume actually persists logos
across a container restart.

```bash
export DB_PASSWORD=$(openssl rand -base64 24)   # or any real password — no default is provided
docker compose up --build
```

## Roadmap (explicitly deferred, not forgotten)

- Billing/invoicing on the admin onboarding pipeline (currently lead-tracking only)
- Coupon-code engine (menu items support per-item flat/percent discounts only)
- Ratings, demand forecasting, delivery-to-class — called out as "Next Phase" in the
  original course PPT, still future scope here
- Canteen owner/staff permission split (one `CANTEEN_STAFF` role today)
- A real custom domain + wildcard DNS/TLS if subdomain-per-college ever comes back — the
  code was actually built that way first and deliberately reworked to account-based tenancy
  instead, since standing up real DNS/hosting wasn't in scope yet

## Known limitations

- **No Testcontainers.** Docker isn't available in this dev environment, so the security
  tests run against a real local MySQL database (`bitesite_test_db`) rather than a
  throwaway container. Test data is namespaced with a random suffix per run so repeated runs
  don't collide on unique constraints, but the database itself isn't reset between runs. CI
  sidesteps this with a real MySQL service container instead.
- **Docker artifacts are unverified** (see [Docker](#docker) above) — genuinely blocked by
  this sandbox, not a known issue with the artifacts.
- **Local-disk file storage is still the default.** Cloudinary is available as a drop-in
  alternative (`UPLOAD_STORAGE_TYPE=cloudinary`) for anything ephemeral or multi-instance,
  but nobody's supplied real Cloudinary credentials yet, so it's only been exercised against
  the local-disk path end-to-end.
- **Privacy policy and terms are honest drafts, not legally reviewed.** They accurately
  describe what the app collects and does today, but they're not a substitute for an actual
  lawyer before this handles real students' data.
- **No horizontal-scaling smoke test.** Sessions, rate limiting, and payment idempotency are
  all designed to be safe across more than one app instance (nothing is held in JVM memory
  that matters), but that's never actually been proven by running two instances behind a
  load balancer — only reasoned about and unit-tested in isolation.
- **Refunds are untested against the live Razorpay API.** The refund call
  (`PaymentGateway.refund`, wired into cancelling a paid order) is implemented against the
  real Razorpay Java SDK and unit-tested with a mocked gateway, but no Razorpay test-mode
  credentials were available while building this, so it's never actually round-tripped
  against Razorpay's servers. Verify it with a real test-mode payment before relying on it.
- **Image uploads no longer accept SVG.** It was previously allowlisted alongside PNG/JPEG/
  WebP; removed because SVG can embed `<script>` and uploaded files are served directly from
  `/uploads/**` with no sandboxing — a stored-XSS vector via a malicious "logo" upload. If SVG
  support is needed later, it needs real sanitization (e.g. strip `<script>`/event handlers)
  first, not just a content-type check.
- **No lawyer has reviewed anything**, including the tenant-onboarding flow, which currently
  has no terms-of-service acceptance step at all when a college is converted from a lead to a
  live tenant — worth adding before onboarding a paying institutional customer.
