# BiteSite: stress test results, requirements and limits

*2026-09-18. Measured on a MacBook Air (Apple silicon) against local MySQL 9.7 and the app on
JDK 21. Production runs on Azure App Service with a Burstable Azure MySQL in another region,
so the **correctness** results carry over and the **speed** numbers do not.*

## Summary

- A full lunch service (3 colleges × 3 canteens × 130 orders, 15 minutes, lunch-rush shaped)
  ran three times over real HTTP. **No server errors and no security refusals in ~125,000
  requests**, and every rupee reconciled.
- It found **four real problems**. One is fixed and live (the login limit); three are open.
- A student phone needs nothing special. The network is what decides how fast the app feels.

## How it was tested

`LongRunningOrderSoakTest` (run with `mvn -Dstress=true -Dtest=LongRunningOrderSoakTest test`):

| Actor | Count | Does |
|---|---|---|
| Students | 160 per college, own session and IP each | Browse, add 1–3 dishes (pick another if sold out), try the promo, check out, pay, cancel sometimes, poll the order, show the pickup code |
| Staff | 2 per canteen | Poll the kitchen queue, start, mark ready, hand over against the code; sometimes cancel an order or remove an item |
| Admin | 1 | Keeps the dashboard and analytics open |

Payment confirmations arrive the ways Razorpay really sends them: callback then webhook,
webhook then callback, both at once, callback only, webhook only, and webhooks sent twice.
Only the gateway is simulated; everything else is the real app.

Afterwards it audits every order: one captured payment per paid order, refunds sent to the
gateway equal refunds owed, totals add up, promo and daily caps held, tokens and live pickup
codes unique, nothing crosses a college or canteen.

## Results

| | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| Orders created | 999 | 1,170 | 1,170 |
| Completed per canteen (floor 100) | 93–106 | 112–117 | 110–119 |
| Requests | 38,841 | 43,450 | 43,124 |
| Server errors (5xx) / security refusals (403) | 0 / 0 | 0 / 0 | 0 / 0 |
| p95 latency (typical interval) | 25–70ms | 25–40ms* | 23–40ms |
| Peak DB connections in use (of 10) | 9 | 10* | 4 |
| Result | Test-model issue (fixed) | Stall + order without pickup code | Double status change |

\* Run 2 had one 30-second stall; see below.

### What held, every run

- **Money.** Refunds sent always matched refunds owed. With ~450 racing callback/webhook pairs
  and ~860 duplicated webhooks, no order was paid or captured twice.
- **Caps.** Promo codes stopped at exactly 40 uses. The capped dish stopped at exactly 30 per
  canteen, zero overshoot.
- **Identity.** No duplicate tokens, no two live orders at one counter with the same pickup code,
  no order or line in the wrong college or canteen.
- **Stability.** Heap stayed between 75 and 195MB; latency did not creep over 15 minutes.

## Problems found

| # | Problem | Evidence | Status |
|---|---|---|---|
| 1 | **Login limit refused students with the right password.** Every login counted, 10 per IP per 5 min, so the 11th student on shared campus Wi-Fi was locked out. | Reproduced locally; the new test fails at student 11 on the old code | **Fixed and deployed** (`c3aa66b`): only wrong passwords count, 10 per network+account and 100 per network |
| 2 | **Two staff can move the same order at once.** Both taps succeed; a second "ready" re-issues the pickup code, so the code in the first notification stops working. | 11 orders (run 2), 6 (run 3) reached READY twice | Open. `OrderService.advanceStatus` needs a row lock or a status check in the UPDATE |
| 3 | **"Ready" is saved before the pickup code.** Two separate writes; a student can see "ready" with no code, and if the second write fails the order can never be handed over. | 5-second gap observed under load (order 21954) | Open. Same fix as #2: one transaction |
| 4 | **Saved-cart deadlocks.** Concurrent cart saves deadlock and the save is dropped; a cleared cart can come back after checkout. | 19 (run 2) and 10 (run 3) per run | Open. `SavedCartDaoImpl.save`: READ COMMITTED plus a retry |
| – | **One 30-second connection-pool stall.** All 10 connections busy, 165 requests waiting, slowest 18.4s; recovered by itself. | Run 2 only, at 13:08 | Cause unknown. Session cleanup was tested as the cause and ruled out. The test now snapshots MySQL if it recurs |
| – | Second staff tap on an order shows an error page instead of a queue message. | 19–102 per run | Minor UX |

## What a student's phone needs

| | Minimum | Comfortable |
|---|---|---|
| Android app | Android 7.0 (minSdk 24) with Android System WebView 88+ | Any phone still getting Chrome updates |
| Android browser | Chrome 88+ | Current Chrome |
| iPhone (website only) | iOS 15.4+ | iOS 16.4+ for order notifications (site added to Home Screen) |
| CPU / RAM | Not a constraint: slowing the CPU 4× changed menu load time by nothing measurable | – |

The floors come from features the code uses (`?.`, flex `gap`, `aspect-ratio`, `<dialog>`),
checked against browser support tables, not tested on those old versions.

**Canteen phones** (image cropper): a 12MP photo opens in 0.1–0.4s and saves in 0.3–0.9s even
at 6× slower CPU; a 48MP photo takes up to 1.8s to open. A 48MP photo needs ~190MB of memory
to decode, so staff shooting in 48/50MP mode should use a phone with 3GB+ RAM.

## What the network needs

Menu page, first visit (~400KB), measured with throttling:

| Network | First visit | Repeat visit (cached) |
|---|---|---|
| 2G: 280 kbps, 800ms | 13.3s | 0.1–0.4s |
| Slow 3G: 400 kbps, 400ms | 9.0s | 0.1–0.4s |
| Congested 4G: 1.6 Mbps, 150ms | 2.4s | 0.1–0.4s |
| Good 4G: 10 Mbps, 60ms | 0.5s | 0.1–0.4s |

Production adds ~0.15–0.2s of server time per page, and ~1s of DNS on a very first visit.

- **Usable** from ~400 kbps. **Comfortable** from 1.5 Mbps with under 150ms latency.
- **Photo-heavy menus are slower**: card photos are not lazy-loaded, so a 30-photo menu adds an
  estimated 0.9–1.8MB (not measured; the demo menu has one photo).
- Uploads are small now: a cropped menu photo is ~155KB (≈3s on 3G), down from multi-MB originals.

## Limits of the system

| Limit | Value | Where |
|---|---|---|
| Throughput sustained locally | ~100 req/s at the rush peak, p95 ~25ms | Soak run 3 |
| Database connections | 10 per instance | `DB_POOL_MAX`, default 10 |
| Wrong passwords | 10 per network+account, 100 per network, per 5 min | `LoginRateLimitFilter` |
| Checkouts | 5 per student per minute | `CheckoutController` |
| Student cancel window | 20s by default (0–300s, admin setting); the kitchen does not see the order until it closes | `OrderSettings` |
| Unpaid order expiry | 15 min | `app.order.payment-timeout-minutes` |
| Daily dish cap | Best-effort by design; can overshoot by orders in flight at the same instant. Measured overshoot: 0 | `OrderService` |
| Upload size | 5MB before cropping; cropping makes most photos far smaller | `spring.servlet.multipart` |
| Session | 30 min idle on the web; 30 days in the Android app | `spring.session.timeout` |

## Not covered

- Production itself. Load was never pointed at it: one Burstable core serves live colleges.
- Whether production sees each student's real IP (the database query was blocked by Azure's
  firewall). The login fix makes this matter far less either way.
- Safari, real Android devices, and the Capacitor WebView.
- The cause of the one 30-second stall.
