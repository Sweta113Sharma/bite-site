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
- **Razorpay** (Java SDK) for payment
- **Spring Boot Actuator** for health/metrics

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
  script.
- **Audit log** (`audit_log` table) on price/discount changes, order status changes,
  tenant/user changes, grievance resolution — viewable at `/admin/audit-log`.
- **Rate limiting**: login (10 attempts / 5 min per IP) and checkout (5 attempts / min per
  user) via an in-memory limiter (`RateLimiter`) — see [Known limitations](#known-limitations)
  for why this is IP/JVM-local, not distributed.
- **Global exception handling** (`GlobalExceptionHandler`): every uncaught exception
  becomes a branded error page (or a structured JSON body under `/api/**`) — never a raw
  stack trace in the response.
- **File storage** for tenant logos is behind a `FileStorageService` interface; the only
  implementation today is local-disk, with an S3-backed implementation as the obvious next
  step before a real multi-instance deployment.

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

Flyway creates the schema and seed data automatically on first boot — no manual SQL needed
beyond creating the empty database.

### Run it

```bash
mvn spring-boot:run
```

Visit **http://localhost:8080/login**. Spring Boot DevTools is on the classpath, so Java
changes trigger an automatic restart and template/CSS changes are picked up on the next
request (after `mvn compile` if you're not using an IDE that compiles on save).

### Seed accounts

Every seeded account shares the password **`Demo@12345`**. Two independent demo colleges
are seeded so you can immediately see that switching accounts switches which college's data
you see:

| Email | Role | College |
|---|---|---|
| `admin@bitesite.local` | SUPER_ADMIN | — (platform) |
| `tech@bitesite.local` | TECH_MANAGER | — (platform) |
| `canteen@demo.local` | CANTEEN_STAFF | Demo College |
| `student@demo.local` | STUDENT | Demo College |
| `canteen@second.local` | CANTEEN_STAFF | Second College |
| `student@second.local` | STUDENT | Second College |

**Change or remove these before this ever runs anywhere but local development.**

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

## Tests

```bash
mvn test
```

25 tests: pure unit tests for the order state machine and discount pricing, Mockito-based
service tests for checkout/payment-confirmation logic, and — the most important one —
`TenantIsolationSecurityTest`, which runs the real Spring Security + MVC stack against a
live MySQL test database and proves a student from one college gets a 404, not their data,
when trying to view another college's order.

The security test needs its own database (kept separate from your dev data on purpose):

```bash
mysql -u root -e "CREATE DATABASE bitesite_test_db CHARACTER SET utf8mb4;"
```

## Docker

A `Dockerfile` (multi-stage: Maven+JDK21 build → JRE-only runtime) and `docker-compose.yml`
(app + MySQL 8.4) are included. **These are written but not executed in this environment —
Docker isn't installed here.** Before relying on them: build the image, confirm the app
boots and Flyway migrates cleanly against the compose MySQL service, and confirm the
`uploads` volume actually persists logos across a container restart.

```bash
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

- **Rate limiting is in-memory, per-JVM instance.** Fine for one server; horizontally
  scaling the app needs a shared store (Redis) instead, or requests get rate-limited
  independently per instance.
- **No Testcontainers.** Docker isn't available in this dev environment, so
  `TenantIsolationSecurityTest` runs against a real local MySQL database
  (`bitesite_test_db`) rather than a throwaway container. Test data is namespaced with a
  random suffix per run so repeated runs don't collide on unique constraints, but the
  database itself isn't reset between runs.
- **Docker artifacts are unverified** (see above).
- **Logo storage is local-disk.** Fine for one server; needs an S3-backed
  `FileStorageService` implementation before running more than one app instance.
