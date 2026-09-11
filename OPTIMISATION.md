# BiteSite — Optimisation Status

**Last updated:** 2026-09-10
**Companion to:** [performanceaudit.txt](performanceaudit.txt) (the original 2026-09-08 audit, with full methodology)

This file answers one question: how fast is the app right now, what made it that
way, and what is left. Every number here was measured, not estimated. Where
something is inferred rather than measured, it says so.

---

## How to read the numbers

All figures come from a local instance running production code, MySQL on the same
machine, with seed data (1 tenant, 1 outlet, 6 menu items).

**Production is slower than this, and by an unknown factor.** Azure MySQL Flexible
Server is on Burstable tier, across a network. Every database statement counted here
costs a network round trip there that it does not cost locally. So statement counts
*understate* production latency. The ratios hold; the absolute milliseconds do not.

Production has deliberately never been load tested. It has a live college on it and a
database whose first connection already eats 6.4s of a 10s budget. Generating load
against that is how you cause an outage, not how you find one.

---

## Where the app stands today

### Bytes on the wire

| Page | Raw HTML | Gzipped | Saving |
|---|---:|---:|---:|
| `/login` | 6,116 | 2,172 | −64% |
| `/student/menu` | 39,856 | 4,526 | −89% |
| `/student/cart` | 15,463 | 3,625 | −77% |
| `/student/orders` | 45,879 | 3,883 | −92% |

Assets a student pulls on a **first** visit — **measured against production on
2026-09-10, after the work in items 5 to 8**:

| | Requests | Wire bytes |
|---|---:|---:|
| `/login` HTML | 1 | 2,441 |
| `app-bundle.css` (was 9 files) | 1 | 60,682 |
| `bootstrap.min.css` (subset, was jsDelivr) | 1 | 9,174 |
| 3 scripts | 3 | 24,600 |
| Archivo ×2 + icon font | 3 | 62,636 |
| **Total** | **9** | **159,534** |

Everything comes from **one origin**. Before this day's work the same visit was
**18 requests across 4 origins and 618,286 bytes** — the difference is 458,752 bytes,
**74%**, three TLS handshakes, and HTTP/2 now enabled.

### Database work per request

| Request | Statements | Of which overhead |
|---|---:|---|
| Anonymous page (`/login`) | 5.3 | was 11.3 before the cart fix |
| Student menu render | 18.3 | 9 transaction churn, 2 session read |
| Outlet queue poll | 14.2 | 9 transaction, 3.1 session, **2.1 actual work** |

The headline problem is unchanged from the audit: **roughly half the database work on
a page render, and 80% on a poll, is session bookkeeping rather than answering the
request.**

---

## What has been done

### 1. Response compression — the largest single win

`server.compression` was off entirely; every page went out raw. One config block took
89% off the student menu.

**Fixed on 2026-09-10:** the MIME list named only `application/javascript`, but
**Tomcat 10 serves `.js` as `text/javascript`** (it changed to follow the WHATWG
spec). The list matched nothing, so every byte of JavaScript still shipped
uncompressed for two days after "compression" was turned on. It fails silently — the
response is correct, just large.

| Asset | Before | After |
|---|---:|---:|
| `app.js` | 76,820 | 21,880 |
| `active-order-strip.js` | 4,100 | 1,672 |
| `password-toggle.js` | 1,269 | 630 |

That took the first-visit asset payload from ~150KB to 93KB. Both media types are now
listed. → [`application.yml`](src/main/resources/application.yml)

**Lesson worth keeping:** a MIME-type allowlist that silently matches nothing is
invisible in testing. The response is valid; only its size is wrong. Verify
compression by reading `Content-Encoding` off a real response, never by reading the
config.

### 2. The cart stopped writing a BLOB on every page view

`GlobalModelAttributes.cartItemCount()` read the session-scoped `Cart` on every render,
unguarded. Merely *reading* a session-scoped bean makes Spring re-`setAttribute` it at
request end, which marks it dirty, and Spring Session JDBC then writes a Java-serialized
object to MySQL.

An anonymous `GET /login` therefore issued four writes — including
`x'aced...com.bitesite.service.Cart'` — for a visitor with no account and no cart.
Anonymous traffic is bots, link previews and probes. Writes are the expensive operation
on Burstable tier.

- Anonymous page: **11.3 → 5.3 statements** (−53%)
- `/login` p50: **43ms → 17ms** (−60%)

The interesting part: guarding it alone broke 23 tests. That read was accidentally
load-bearing — it created the session early, and `fragments/head` reads `_csrf?.token`
during rendering. With the read gone, the token was created mid-render against an
already-committed response. `CsrfTokenEagerFilter` now makes that ordering explicit.

### 3. The cart moved out of `@SessionScope` entirely

State now lives in the session under one key, written only when it actually changes.
Reads never write. Public API unchanged, so no call site moved.

- Student menu render: **22.3 → 18.3 statements**

This removed the cart write, the session touch, and one of the four session
transactions. → [`Cart.java`](src/main/java/com/bitesite/service/Cart.java)

### 4. Templates stopped shipping their own comments

Thymeleaf renders `<!-- -->` into output. 27% of the student menu (14,850 of 54,640
bytes) was commentary intended for developers, sent to every user on every page load.
All 126 comments converted to the parser-level `<!--/* */-->` form, which is stripped
at render.

**Not one word was removed from the source.** The reasoning in those comments has
concrete value — the verbose comment on `activeOrders()` is what made the missing guard
on `cartItemCount()` visible, which was the biggest finding in the whole audit.

### 5. The icon font was never actually subset — 377KB → 9KB

Found 2026-09-10 while looking for wins on bad campus networks. The font was **80% of
everything a student downloaded on a first visit**, and it was carrying 5,514 outlined
glyphs for the 64 icons this app draws.

`scripts/subset-icon-font.py` existed, ran, and reported success. It called
`populate(glyphs=..., text=...)`, and the `text` argument was the bug: fontTools walks
GSUB from the glyphs it is handed, so giving it the letters that spell the icon names —
with layout closure left on — re-added every ligature those letters could form, which is
every icon in the font. The axis pinning that followed genuinely cut 3.98MB to 377KB, and
that number read as success. The glyph subsetting had done nothing.

| | Before | After |
|---|---:|---:|
| Icon font | 377,088 | 9,104 |
| **First-visit assets** | **470,164** | **102,180** |

`opts.layout_closure = False` is the fix. 88 glyphs: 64 icons plus the 23 letters that
spell them.

**Why it hid for so long:** a font that is 40× too large is invisible to glyph counts, to
file sizes, and to every name-level assertion in `IconGlyphCoverageTest` — the manifest
contract was about names, and the names were all correct. The script now shapes all 64
names through HarfBuzz against the source font and **refuses to write** an output whose
glyphs differ. `IconGlyphCoverageTest` also gained a size ceiling, verified by putting the
old font back and watching it fail.

One near-miss worth recording: an earlier attempt kept only `liga,dlig,calt,rlig` and
dropped `rclt`. This manifest has two names that prefix another — `check`/`check_circle`
and `person`/`person_add` — and that is exactly what the script's own docstring warned
about. Keeping every layout feature shapes identically to the source for all 64. The
shaping check caught it; reasoning about the spec would not have.

### 6. Already right before any of this started

- **Queries are indexed and tenant-scoped.** The reorder rail was checked against
  10,000 orders across 300 users: MySQL picks `idx_orders_user` and examines 34 rows,
  not a scan. No N+1 loops on any page measured.
- **Aggregation happens in SQL, not Java.** `sumQuantitiesByMenuItemToday` and
  `findFrequentMenuItemIds` both push work into the database that a naive
  implementation would do by loading entities.
- **HikariCP is configured deliberately**, with `leak-detection-threshold` set. The
  comment records why: an instance was once found holding 135 connections against a
  server allowing 151.
- **Static assets are content-hashed** and excluded from the security filter chain
  entirely, so an image request cannot start a session. That exclusion turned out to
  matter.
- ~~**The icon font is subset**~~ — **this was wrong, see item 5 above.** The axis
  pinning was real and did cut 3.98MB to 377KB; the glyph subsetting silently did
  nothing, and the font shipped with 5,514 glyphs for 64 icons.
- **Pollers request JSON, not HTML fragments**, and leave the DOM alone when nothing
  changed.

---

## What is left, in the order worth doing it

### 7. One stylesheet instead of nine, and the fonts preloaded

All nine parts were render-blocking, so nothing painted until the last arrived — over an
HTTP/1.1 origin that caps a browser at ~6 connections, each with its own TLS handshake.
`scripts/build-css-bundle.py` concatenates them in cascade order.

Safe *because* the order was already the cascade: a stylesheet does not care whether two
rules arrived in one file or two. That equivalence needs `@import`, `@charset` and relative
`url()` to stay absent from the parts, so `CssBundleTest` asserts all three, and asserts the
bundle still matches the parts — a part edited without regenerating would otherwise never
reach a browser with nothing else noticing.

Also 13% smaller: gzip compresses one file against a single dictionary instead of restarting
nine times. 69,159 bytes as nine responses, 59,994 as one.

The two `latin` faces are now `rel=preload`. A font is otherwise not requested until CSS has
parsed and layout has found an element needing it. `crossorigin` is required even same-origin
or the preload is not reused and the file downloads twice.

**Item C below was measured and rejected.** 50 selectors in `09-console.css` are NOT scoped to
`body.console` — `.btn`, `.badge`, `.bg-white` and other Bootstrap overrides — so a student
page skipping that file would lose them. The audit's own caveat was the operative one.

### 8. JavaScript was still not compressed in production

Item 1 records this as fixed on 2026-09-10. **The fix was never committed.** It sat in the
working tree while production went on serving every byte of JavaScript raw for two more days.
Caught by comparing headers on the live site rather than trusting the note:

```
/css/app-bundle-...css   Content-Encoding: gzip
/js/app-...js            no Content-Encoding, Content-Length: 76,820
```

82,189 bytes of raw JavaScript per first visit against 24,182 compressed. `app.js` alone went
76,820 → 21,880 once deployed.

**The lesson from item 1 needed one more clause.** "Verify compression by reading
`Content-Encoding` off a real response" is right, and it still missed this, because the
response that was read was a local one. Read it off **production**, after the deploy that was
supposed to fix it.

### 0. HTTP/2 is not enabled — new finding, 2026-09-10

Measured, not assumed: `curl --http2 https://app.bitesite.in/login` negotiates **1.1**,
while `cdn.jsdelivr.net` negotiates **2** from the same machine and the same curl. Azure
App Service has HTTP/2 off by default (`http20Enabled`).

This matters more than anything else left on this list *for the stated problem*, because
HTTP/1.1 caps a browser at roughly six connections per origin and gives each its own TLS
handshake. A first visit pulls 9 stylesheets, 2 scripts, the font and the icons from our
origin — so on a high-latency campus link those queue in waves instead of multiplexing
over one connection.

It is one setting: `az webapp config set --http20-enabled true`. **But it recycles the
app**, and with a ~320s boot against a Burstable database that is a real, if brief,
outage. Worth doing in a quiet window, not casually.

### 0b. RESOLVED — the three third-party origins are gone

Bootstrap used to come render-blocking from `cdn.jsdelivr.net`, and Archivo from
`fonts.googleapis.com` (a render-blocking stylesheet) which then sent the browser to
`fonts.gstatic.com` for the files. Three DNS lookups, three TCP connects and three TLS
handshakes with hosts the browser had never spoken to, before first paint. `preconnect`
warmed that work; it could not remove it.

Both are served from our own origin now, so every byte a page needs arrives over the one
connection it already has open. Archivo is copied from what Google served, `unicode-range`
and all, so a page still fetches only the subsets it uses — and Google serves Archivo as
**one variable file covering 400-700**, not four static weights.

Side effects worth knowing:

* They are behind the service worker's cache-first handler now. `sw.js` deliberately leaves
  third-party requests to the network, so these were previously the only assets a returning
  student on a dead connection could not get.
* The CSP dropped all three origins from `script-src`, `style-src` and `font-src`. Razorpay
  is the only third party left in the policy, and it has to be.

### A. Halve the outlet poll — one constant, biggest sustained win

`POLL_INTERVAL_MS = 5000` in
[`queue-poll.js:4`](src/main/resources/static/js/queue-poll.js#L4).

One outlet console open for a 12-hour shift:

```
12 polls/min × 720 min × 14.2 statements  ≈ 123,000 statements
of which roughly                          ≈  97,000 are session overhead
```

This is the largest sustained load in the system and **it scales linearly with every
canteen onboarded**. Going to 10s halves it for a delay no human notices at a counter.

*Risk: low, one constant. But staff see it, so it is a product call, not a technical
one.*

### B. Collapse the remaining session transactions — largest technical item

9 of the 14.2 statements in a queue poll, and 9 of the 18.3 in a page render, are
Spring Session wrapping each operation in its own transaction.

`TransactionOperations.withoutTransaction()` removes the churn, but it is a
**constructor** argument of `JdbcIndexedSessionRepository` — the existing
`SessionRepositoryCustomizer` in
[`SessionStoreConfig.java:43`](src/main/java/com/bitesite/config/SessionStoreConfig.java#L43)
cannot reach it. It has to be supplied to `JdbcHttpSessionConfiguration` before the
repository is built, which means overriding how Spring Boot autoconfigures it.

**The trade:** this drops atomicity on multi-statement session saves. Session data is
ephemeral and self-healing, so it is defensible — but it is a real trade and should be
a deliberate decision, not a silent one.

*Risk: medium. The change is small; the wiring is not, and getting it wrong silently
breaks session persistence for everyone.*

### C. Every page ships the console stylesheet — new finding, 2026-09-10

[`fragments/head.html:64-72`](src/main/resources/templates/fragments/head.html#L64-L72)
links all nine stylesheets on every page, for every role.

A student downloads:

| Stylesheet | Gzipped | Used by a student? |
|---|---:|---|
| `09-console.css` | 12,073 | No — staff consoles only |
| `04-auth.css` | 4,738 | No — login screen only |
| | **16,811** | **24% of the CSS payload** |

Only costs a first visit, since these are content-hashed and cached forever. **Do not
just split the files:** every one of them carries a header saying the load order *is*
the cascade and a rule can only override one in an earlier file. Splitting by portal
means first proving no student-facing rule actually lives in `09-console.css`.

**REJECTED 2026-09-10 — see item 7.** Verified before acting, as the note said to: 50 selectors in `09-console.css` are not `.console`-scoped, so students need the file. Bundling all nine was done instead.

### D. `/student/orders` has doubled since the audit

45,879 bytes raw, against 22,929 when the audit was written. Gzip hides it well (3,883
on the wire) but the server still renders all of it, and it is 5× Tomcat's 8KB output
buffer — which is what keeps the double session commit alive (see below). Worth finding
out where the bytes went.

*Risk: low. Investigate before changing anything.*

### E. The session is still committed twice per request

Traced on one student request:

```
[txn 1]  SESSION READ
         ...6 business queries...
[txn 2]  UPDATE SPRING_SESSION      <- commit #1
[txn 3]  SESSION READ (again)       <- cache cleared, re-read
[txn 4]  CART WRITE                 <- commit #2
```

The page is far larger than Tomcat's 8KB output buffer, so the response commits
mid-render; Spring Session's `onResponseCommitted` fires and clears its cache.
Compression does **not** help — the buffer counts characters written *before*
compression. Item D shrinks the cause; item B removes most of what it costs.

Raising the buffer to 64KB would make this disappear, but it costs memory per
concurrent request and treats the symptom.

### F. A tenant lookup on every authenticated request

[`TenantResolutionInterceptor.java:40`](src/main/java/com/bitesite/config/TenantResolutionInterceptor.java#L40)
issues `SELECT * FROM tenants WHERE id = ?` per request. A primary-key hit on a tiny
table, so it is nearly free locally — but it is a network round trip in production, on
every request, for data that changes approximately never.

*Do last, or not at all.* It needs invalidation design so suspending a college takes
effect promptly rather than after a TTL, and no cache dependency exists in the project
today. Adding one for a single primary-key lookup is not obviously worth it.

---

## Not yet measured

Honest gaps, so nobody mistakes silence for a clean bill of health.

- **The analytics dashboard** (added after the audit) runs 6–7 aggregate queries per
  load, filtering on `token_day`. `idx_orders_token_day` exists, so the shape is right,
  but no query plan has been checked at volume and the local dataset is far too small
  to expose a bad one.
- **Billing, settlement and promo-code queries** are all indexed
  (`idx_orders_settlement`, `idx_redemptions_user`) and were verified for correctness,
  but never profiled.
- **No page has been measured on a real mobile connection.** Every figure here is
  bytes and statement counts, not perceived load time on campus 4G.

---

## The part code cannot fix

### The database is on Burstable tier

`bitesite-db-01`, MySQL 8.0.21, Burstable. This caused the outages on 2026-09-05 and
2026-09-07: a slow first connection killed startup outright, because Flyway's
`connect-retries` defaults to 0.

Retries were added, sized to App Service's 230s startup budget — but they are a safety
net, not a fix. On the **healthy** boot of 2026-09-08 the first connection still took
6.4 seconds against a 10 second Hikari timeout. Two thirds of the budget consumed on a
good day.

Everything in the section above reduces the number of round trips crossing that link,
which is the only leverage code has here. **The actual fix is a tier that is not
Burstable** — which is what the OCI Ampere A1 migration was chasing. That hunt is
currently stopped: ~50 launch attempts across two runs, Oracle had no capacity in
Hyderabad on any of them.

### Boot time

Local boot is 2.6 seconds. Production takes about 320. That gap is Burstable plus a
cold JVM, not application code. No CDS or AOT is configured; Class Data Sharing is the
obvious next lever if boot time becomes the binding constraint.

`healthCheckPath` is deliberately **off**. It was tried and reverted: with a 320s boot,
Azure cannot tell a wedged instance from one that is still starting, and a config change
is itself a restart — so enabling it during a slow boot resets the clock and extends the
outage it was meant to detect. Revisit *after* boot time comes down, not before.

---

## Summary

The queries were never the problem. Two things were:

1. **Nothing was compressed** — fixed for HTML and CSS on 2026-09-08, and for
   JavaScript on 2026-09-10 after the MIME-type mismatch was found.
2. **The session machinery costs more than the pages do** — roughly 70% of the database
   work on a render and 80% on a poll. The cart fixes removed half of it; the rest is
   item B.

The single highest-value change remaining is item A, because it is one constant and it
is the only cost in the system that grows with every canteen onboarded.
