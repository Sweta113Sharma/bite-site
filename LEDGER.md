# BiteSite — Work Ledger

A running record of what changed, when, and why. Newest first.

---

## The rule

**Every commit gets an entry here, written at the time of the commit — not later.**

An entry is not optional and not a nicety. Six weeks from now the commit message will
say *what* changed and this file is the only place that says *why it was worth doing*
and *what it might have broken*. A commit with no entry is work nobody can audit.

### What an entry must contain

```markdown
### `<short-sha>` — <commit subject>
**Date:** YYYY-MM-DD · **Scope:** N files · **Deployed:** yes / no / n-a

**What changed**
- Plain-language bullets. What a user or an operator would notice.

**Why**
- The reason it was worth doing. Skip only if the commit subject already says it.

**Verified by**
- How you know it works. Tests run, endpoints driven, figures measured.
- "Compiles" is not verification. Neither is "should work".

**Watch out for**
- Anything that could bite: a migration, a config change, a behaviour change
  someone will notice, a known gap. Write "nothing" if there genuinely is nothing.
```

### Rules for writing one

1. **Record what actually happened, not what was intended.** If a step was skipped,
   say so. If tests failed and were fixed, that is worth a line. If something is
   half-done, name the half that is missing.
2. **Separate verified from reported.** Work you drove yourself is verified. Work
   handed to you as a summary is reported — mark it, and say what you confirmed
   independently.
3. **Never record a secret.** Keystore passwords, API keys, connection strings and
   database credentials do not belong here. Record that a keystore *exists*, never
   what unlocks it.
4. **Deployments get their own line** in the entry for the commit that shipped, with
   the outcome. A deploy that failed or was rolled back is more important to record
   than one that worked.

---

## 2026-09-11

### `6a27504` — Settle a refund from Razorpay's own record instead of waiting for a human
**Date:** 2026-09-11 · **Scope:** 17 files · **Deployed:** yes

**What changed**
- `refund.processed` / `refund.failed` webhooks are handled. They arrive whether or not
  our HTTP call heard the answer, so a timed-out refund now settles seconds later: the
  payment goes REFUNDED and the order is cancelled with the reason the cancel was
  originally asked for. `refund.failed` returns the payment to CAPTURED and flags it,
  because a failed refund means the money never left.
- A sweeper (every 5 min) asks Razorpay about anything still REFUND_PENDING past
  `app.refund.reconcile-after-minutes` (default 10). It settles what Razorpay confirms,
  re-sends what Razorpay never received (3 attempts total), and flags the rest.
- V34 records what a refund is *for* — reason, actor, attempt count, last attempt time —
  so whoever settles it later knows what the order is owed. That intent previously lived
  only in the local variables of the call that died.
- A REFUND_PENDING row with no Razorpay payment id is flagged and skipped rather than
  retried forever. Found by the sweep hitting exactly that in the test database.

**Why**
- V33 made a timed-out refund honest (stuck, flagged, never double-refunded) but could not
  finish it. Only a person with the Razorpay dashboard could, and nothing in the app could
  resolve the state at all: `clearReconciliation` had no caller anywhere.

**Verified by**
- `mvn test` locally: full suite green, 500+ tests, existing concurrency tests included.
  CI green on the same commit.
- New `RefundReconciliationTest`, 6 tests, full-stack against MySQL: timed-out refund
  settled by the sweep with the order cancelled and its reason intact; a request Razorpay
  never received re-sent; a refund too recent left untouched; `refund.processed` settling a
  timeout through the real endpoint; `refund.failed` returning money to CAPTURED; a partial
  refund flagged rather than settled.
- **Deployed 2026-09-11 05:02 UTC**, run 34564429811. V34 applied in production:
  "Successfully applied 1 migration to schema `bitesite_db`, now at version v34". App
  started in 108.9s, health UP, no errors in the startup log. The Burstable-tier MySQL
  boot failure did not recur this time.
- **Not verified against Razorpay.** No call was made to their API from here. The webhook
  payload shape is taken from their documentation and the sweep is proved against a stub.
  End to end needs a captured test-mode payment, which needs a browser.

**Watch out for**
- **V34 migration**, and the Razorpay dashboard must be sending `refund.processed` and
  `refund.failed` (ticked 2026-09-11, alongside the existing `payment.captured`). Without
  them only the sweep settles anything.
- The sweep is **global, not tenant-scoped**. Deliberate: it is platform-level recovery.
- **First real refund after this deploy is the actual proof.** Look for `settled via
  webhook refund.processed` (normal) or `settled via reconciliation sweep` (the webhook
  did not arrive — check Razorpay's delivery log if it recurs).
- An idempotency key was **not** added. `razorpay-java`'s `addHeaders` writes to a static
  map shared by every client in the JVM and never cleared, so it cannot carry a per-refund
  key safely, and Razorpay does not document how long a refund key is honoured. Safety
  rests on reading back the truth plus Razorpay's own refusal to refund twice ("The
  payment has been fully refunded already").
- `payment.failed` was deliberately **not** ticked. Razorpay sends it per attempt, and
  PAYMENT_FAILED cannot transition to PAID, so marking an order failed on a declined card
  would strand the capture when the student's next card succeeds.

### `24e2e02` — Make refunds safe against a gateway that succeeds while reporting failure
**Date:** 2026-09-11 · **Scope:** 9 files · **Deployed:** yes (2026-09-10 20:22 UTC, run 34525979906)

*Entry written late, on 2026-09-11, by a session that did not carry out the work. The
commit had no entry, against the rule at the top of this file. What follows is taken from
the commit message except where marked.*

**What changed**
- REFUND_PENDING became a real payment state, written and committed **before** the gateway
  is called. It is both the claim that makes refunding exclusive and the honest answer
  after a timeout: we asked, we do not know.
- `refundOrder`, the admin manual path, had no protection at all — two admins pressing
  refund would both fire. It now takes the same claim.
- Cancelling an order whose refund is unresolved is refused, rather than quietly cancelling
  it while the student may or may not have their money back.
- V33 widened the `chk_payments_status` CHECK constraint, which V1 had pinned to five
  statuses.

**Why**
- The previous fix stopped concurrent cancels double-refunding. It did not handle the more
  likely failure: a refund is a network call that can succeed and still throw.

**Verified by**
- Reported in the commit message: 8 concurrent cancels → one gateway call; a timeout leaves
  the payment REFUND_PENDING and flagged with the order still PAID; a second cancel after a
  timeout is refused without reaching the gateway.
- **Independently confirmed 2026-09-11**: `ConcurrentRefundTest` (both cases above) passes
  in the full suite. The behaviour it asserts is real. The figures from the pre-fix runs
  were not re-derived.

**Watch out for**
- **V33 migration.**
- `cancelOrder`, `cancelOwnOrder` and `refundOrder` are deliberately **not** `@Transactional`.
  A `REQUIRES_NEW` claim inside a caller's transaction needs a second connection while the
  first is held, which deadlocked the test pool of four immediately.
- It left refunds *safe* but *unfinished*: nothing in the app could resolve a
  REFUND_PENDING payment. Closed by `6a27504`.

### `4632eac` — Profile the analytics dashboard at volume: it is healthy
**Date:** 2026-09-11 · **Scope:** 1 file · **Deployed:** n-a (test only)

**What changed**
- Opt-in profile of all 7 dashboard queries against 16,474 orders / 32,199 items.

**Why**
- The last item on the audit's "not yet measured" list. The local dataset had always been too small to expose a bad plan.

**Verified by**
- 246 ms for a full dashboard load. Slowest single query 54 ms.
- Plan read by hand: a narrow window uses `idx_orders_token_day` as an index scan (not a range seek), because `GROUP BY token_day` wants the index in order. Sound.

**Watch out for**
- **An EXPLAIN assertion was written and deliberately removed.** Local MySQL is 9.7 (tree format), production is 8.0.21 (classic table), so it would pass here and mean nothing there — the same trap as the pagination tiebreaker.
- The index scan grows with TOTAL orders, not with the window asked for. If timings climb, a composite `(token_day, status)` index turns it into a range seek, at the cost of another index on the insert hot path.

### `6bdb239` — Refund the student exactly once, however many cancels arrive together
**Date:** 2026-09-11 · **Scope:** 4 files · **Deployed:** yes

**What changed**
- `cancelOrder` now claims the refund with a conditional UPDATE before calling the gateway.
- The separate `updateStatus(REFUNDED)` write is gone; the claim records the outcome.

**Why**
- **Proven before fixing: 8 simultaneous cancels asked Razorpay to refund one payment 4 times.** Real money, four times over.
- No constraint could catch it. The money leaves at the gateway before anything local changes, so exclusivity had to come first.

**Verified by**
- After the fix: 8 concurrent cancels, `refund` called once, payment REFUNDED, order CANCELLED.
- `ConcurrentRefundTest` is in the normal suite, not behind `-Dstress`: it takes 3 seconds and guards the only path where losing a race costs money.

**Watch out for**
- **This inverts the old ordering**, which moved money before touching the database so a failed refund could not leave an order cancelled but unpaid. The guarantee now comes from transaction rollback instead. If `cancelOrder` ever stops being `@Transactional`, that protection is silently gone.

### `ea3b79b` — Insert a cart's lines in one round trip instead of one per item
**Date:** 2026-09-11 · **Scope:** 3 files · **Deployed:** yes

**What changed**
- `createOrder` batches its item inserts.
- `rewriteBatchedStatements=true` added to both datasource URLs.

**Why**
- A five-item cart cost five round trips on the checkout path, the most latency-sensitive moment in the product.

**Verified by**
- MySQL general log, two-item cart: 2 INSERT statements before, 1 after, in the multi-row `VALUES (),()` form — the driver confirming it actually rewrote rather than just accepting the batch.
- 600 concurrent checkouts, then every order verified to hold exactly its 2 item rows.

**Watch out for**
- **Either change alone does nothing.** Without the flag, Connector/J still sends each statement separately and the batch is only an API convenience.
- The flag is safe here because neither batch site asks for generated keys. A future batched insert that does would misbehave.

### `212f88c` — Fix two concurrency races found by stress-testing the order flow
**Date:** 2026-09-11 · **Scope:** 6 files · **Deployed:** yes

**What changed**
- V32: generated column + unique index so two live orders at one outlet cannot share a pickup code.
- Token collisions on checkout are now retried instead of failing the student's order.

**Why**
- Pickup code: 4 collisions in ~1,200 simultaneous transitions. The code is the handover authentication, so this is a student collecting someone else's food.
- Token: the constraint already existed, so no duplicate ever landed — but nothing caught the exception, so the loser's **checkout simply failed**. 2 in 300.

**Verified by**
- 4,000 concurrent checkouts across 8 colleges: zero failures, no duplicate tokens or codes, no lost orders, no cross-tenant leakage, no deadlocks.

**Watch out for**
- The migration deduplicates before adding the index, or the ALTER fails and takes the deploy with it. Expected to touch zero rows in production.
- Only 9,000 tokens per college per day, so the retry matters more as volume grows.

### `4287296` — Empty the page cache whenever the user changes, not only on logout
**Date:** 2026-09-11 · **Scope:** 1 file · **Deployed:** yes

**What changed**
- The service worker now purges `PAGE_CACHE` on `/login` and `/api/role/switch` as well as `/logout`.

**Why**
- Cached pages are keyed by URL alone, so one student's page could be served to the next person on a shared device. Logout was the least likely of the three ways a phone changes hands.

**Verified by**
- Code path reviewed against all three auth boundaries in `SecurityConfig`.

**Watch out for**
- **`74aab17` widened this.** Falling back to cache after 2.5s rather than only on network failure made a stale authenticated page reachable on a merely slow connection — the exact condition this app is built for.
- Not verified on a device: needs two accounts on one phone.

### `4c7d0f1` — Give every paginated list a total sort order
**Date:** 2026-09-11 · **Scope:** 10 files · **Deployed:** yes

**What changed**
- Eight paginated queries gained `, id DESC`. `PaginationOrderingTest` enforces it structurally.

**Why**
- `created_at` is second-precision, so rows tie, and MySQL breaks ties differently between executions. A paginated list could show a row twice on page two and never show another. The audit log was one of the eight.

**Verified by**
- Mutation-checked by removing the tiebreaker from the audit log query and watching the structural test fail.

**Watch out for**
- **The behavioural test cannot be trusted to catch this.** With the tiebreaker removed it passed 3/3 locally with every timestamp tied: this machine returns ties in insertion order, CI's MySQL does not. The structural test carries it.
- Found only because CI disagreed with the laptop.

### `ebb058a` — Page the student's order history instead of loading all of it
**Date:** 2026-09-11 · **Scope:** 7 files · **Deployed:** yes

**What changed**
- `/student/orders` is paged 10 at a time with a Show more link; terminal statuses filtered in SQL.
- `attachItems` fetches a page's lines in one query instead of one per order.

**Why**
- The page loaded every order a student had ever placed and attached each one's items separately: 1+N queries, growing forever on a daily-use product.

**Verified by**
- General log: 1 query for a 25-order page where it was 25.
- Batch grouping mutation-checked by regrouping every line onto the first order and watching it fail.

**Watch out for**
- **This commit shipped with CI red** (see `4c7d0f1`).
- The batched fetch benefits every list in the product, not just this page.

### `2c2289c` — Rebuild the CSS bundle after removing the dead toggle rule
**Date:** 2026-09-11 · **Scope:** 1 file · **Deployed:** yes

**What changed**
- Regenerated `app-bundle.css`.

**Why**
- `23d4e6e` edited `04-auth.css` without regenerating, so the change never reached a browser.

**Verified by**
- `CssBundleTest` failed exactly as written to, then passed.

**Watch out for**
- **`23d4e6e` was pushed with the suite red.** The command chained the commit after a `grep` that succeeds regardless of the build result. Chain on the build's exit code.

### `23d4e6e` — Make the password eye actually work everywhere, and by keyboard
**Date:** 2026-09-11 · **Scope:** 8 files · **Deployed:** yes

**What changed**
- `account/change-password.html` now loads `password-toggle.js`; `tabindex="-1"` removed from all five toggles; `aria-pressed` added; dead Phosphor branch and CSS removed.

**Why**
- The eye on the change-password page **had never worked**: the markup was there and no script was loaded at all. All five were unreachable by keyboard.

**Verified by**
- `PasswordToggleWiringTest` enforces script wiring, keyboard reachability and `aria-pressed`; both assertions mutation-checked.

**Watch out for**
- I nearly blamed the CSS for a mis-sized icon. `06-editorial.css` sizes it correctly and wins; the sizing was never the bug.
- Not verified in a browser: whether the eye visibly swaps needs someone to press it.

### `954fae1` — Restore the eye icon, and fix the queue's dead Phosphor icons
**Date:** 2026-09-11 · **Scope:** 5 files · **Deployed:** yes

**What changed**
- `visibility_off` added to the font manifest; `IconGlyphCoverageTest` now scans JavaScript.
- Outlet queue action buttons use `skillet` / `notifications_active` instead of dead Phosphor classes.

**Why**
- **`visibility_off` was a regression I introduced in `b78ee29`** — before the subset the font carried everything, so an undeclared icon still drew. Pressing "show password" rendered the literal word.

**Verified by**
- Independent scan and the extended test both clean; guard mutation-checked.

**Watch out for**
- **The gap was that no test had ever opened a `.js` file.** The icon exists only in a `textContent` assignment.
- The Phosphor icons predate this work: the server drew them correctly and the poller wiped them 5 seconds later.

### `a5fd4da` — Stop the session machinery costing more than the pages do
**Date:** 2026-09-11 · **Scope:** 6 files · **Deployed:** yes

**What changed**
- `springSessionTransactionOperations` bean removes the per-statement session transaction.
- Tenant lookup cached, invalidated on every write in `TenantService`.

**Why**
- Session bookkeeping was ~70% of database work per render. The tenant lookup was a cross-region round trip per authenticated request for data that never changes.

**Verified by**
- MySQL general log A/B: 27 queries down to 12 for 5 `/login` requests, all 10 commits gone. Production TTFB ~411 ms to ~317 ms warm (directional; noisy measurement).

**Watch out for**
- **`OPTIMISATION.md` recorded this as needing an autoconfiguration override. That was out of date** — Spring Session 3.5.1 added the named-bean seam and it is five lines. The bean NAME is the whole mechanism.
- **Trade:** session saves lose atomicity. Defensible here (security context and cart are rebuilt next request); never do this to an order or a payment.
- Tenant cache: on a second instance a suspension could sit stale for up to 60s.

### `7e82e29` — Hunt every fault domain, not just the one Oracle picks
**Date:** 2026-09-11 · **Scope:** 1 file · **Deployed:** n-a (script)

**What changed**
- The OCI hunt cycles unspecified plus all three fault domains.

**Why**
- ap-hyderabad-1 has one availability domain but three fault domains, and capacity is per fault domain. The script only ever asked for one placement.

**Verified by**
- Observed cycling correctly in the log across `<unspecified>`, FD-1 and FD-2.

**Watch out for**
- Does not raise the request rate, so it cannot worsen the 429 throttling.
- **Two corrections to earlier advice: Always Free is locked to the home region (Mumbai is not available), and this tenancy's A1 ceiling is 2 OCPU / 12 GB, not 4/24.**
- First version used `mapfile` and an unguarded array expansion, both of which fail on the bash 3.2 macOS ships. It killed the hunt on attempt 1.

## 2026-09-10

### `71d31f3` — Serve only the Bootstrap this app uses: 31KB to 9KB gzipped
**Date:** 2026-09-10 · **Scope:** 4 files · **Deployed:** yes

**What changed**
- `bootstrap-5.3.3.full.css` is now the vendored original and is never linked;
  `bootstrap.min.css` is generated from it by `scripts/build-bootstrap-subset.py`.
- 232,758 bytes to 49,119 raw; **30,820 to 9,174 gzipped** in production.

**Why**
- Bootstrap shipped ~232KB to deliver the 172 classes this app references: 22KB of gzip
  per first visit for nothing, about 12% of what remained.

**Verified by**
- Of 539 classes the app references, Bootstrap defines 172, and **all 172 survive**.
- Every `--bs-` variable still referenced by a retained rule is still defined, bar four.
  Two of those are undefined in stock Bootstrap too; the other two belong to `.ratio` and
  `link-opacity`, which this app never uses. `ratio` appeared to match at first and turned
  out to be the inside of "registration" and "Operations".
- `BootstrapSubsetTest` mutation-checked by stripping the `.badge` rules and watching it fail.
- 494 tests. Production measured at 9,174 bytes for the served file.

**Watch out for**
- **The full copy must stay.** If the served file were the only one, the first template to
  use a class an earlier purge removed would find it missing with nothing to rebuild from.
- Classes assembled at runtime are the way this goes wrong. The two that exist
  (`queue-poll.js` button and badge lookups) are string literals, so the scan covers
  `static/js` too. Anything genuinely computed would need the safelist.

### `74aab17` — Stop polling and page loads punishing a slow connection
**Date:** 2026-09-10 · **Scope:** 3 files · **Deployed:** yes

**What changed**
- Both pollers replaced `setInterval` with self-scheduling loops: skip while hidden or
  offline, exponential backoff on failure (60s queue, 120s student strip), reset on success,
  immediate retry on regaining focus or network.
- Service worker navigations: network races a 2.5s timer, then serves cache and lets the
  request finish refreshing it.
- Logout now purges the page cache.

**Why**
- An outlet console on a background tab polled every 5s all day. Neither poller knew a
  request had failed, so a dying connection got retried at full rate, which is the worst
  thing to do to it.
- Navigations awaited the network however long it took, so a student saw a blank screen for
  a full round trip with a good cached copy sitting unused.
- Cached pages are keyed by URL alone. On a shared phone the next person to sign in could be
  handed the previous student's order page.

**Verified by**
- 491 tests; both files parse under `node --check`.
- Not verified in a browser: the backoff and cache-timeout behaviour is reasoned from the
  code, not observed on a real slow connection. Worth watching on campus.

**Watch out for**
- Deliberately **not** stale-while-revalidate. That would show a cached copy on every
  navigation, and order status is what students open this app to see.
- Service worker `VERSION` bumped to v4, so clients take the new one on next load.

### `03e1ce2` — Compress JavaScript in production
**Date:** 2026-09-10 · **Scope:** 1 file · **Deployed:** yes

**What changed**
- Added `text/javascript` to `server.compression.mime-types`.

**Why**
- The fix was already written and sitting **uncommitted in the working tree**, so it had
  never been deployed. `OPTIMISATION.md` recorded it as fixed; production had been shipping
  every byte of JavaScript raw for two more days.
- Tomcat 10 serves `.js` as `text/javascript` (changed from `application/javascript` to
  follow the WHATWG spec), so a list naming only the old value matched nothing.

**Verified by**
- Caught by reading headers off the **live site**, not from the config or a local run:
  `/css/app-bundle-….css` returned `Content-Encoding: gzip`, `/js/app-….js` returned no
  encoding and `Content-Length: 76820`.
- After deploy, polled production until the new instance took over (~3 minutes): `app.js`
  now returns `Content-Encoding: gzip` at **21,880 bytes, down from 76,820**.
- All JavaScript: 82,189 raw → 24,182 compressed.

**Watch out for**
- The lesson recorded under "response compression" needed one more clause. "Verify by
  reading `Content-Encoding` off a real response" was right and still missed this, because
  the response read was a local one. Read it off **production, after the deploy**.
- The new instance served the old JAR for roughly three minutes after the workflow went
  green. A check run immediately after a deploy can report the previous build.

### `4038e67` — Ship one stylesheet instead of nine, and preload the two fonts
**Date:** 2026-09-10 · **Scope:** 4 files · **Deployed:** yes

**What changed**
- Nine render-blocking stylesheets became one generated bundle
  (`scripts/build-css-bundle.py`, pinned by `CssBundleTest`).
- The two `latin` font faces are preloaded.

**Why**
- Every part was render-blocking, and this origin serves HTTP/1.1 (measured), which caps a
  browser at ~6 connections each with its own TLS handshake.
- Also 13% smaller: gzip compresses one file against a single shared dictionary rather than
  restarting nine times — 69,159 bytes as nine responses, 59,994 as one.
- Concatenation is safe *because* the load order was already the cascade.

**Verified by**
- 491 tests. `CssBundleTest` pins the bundle to the parts and asserts no part uses
  `@import`, `@charset` or a relative `url()` — the three things that would break the
  equivalence.
- That test immediately caught a case my own grep had missed: a nested `url(%23n%23)` inside
  a `data:image/svg+xml` noise texture in `07-brutalist.css`. It is a false positive for
  bundling (it resolves inside the SVG), and the test now elides data URIs before scanning.
- Production `/login` verified serving exactly two stylesheets, both content-hashed, and no
  third-party URLs at all.

**Watch out for**
- **Splitting per portal was measured and rejected**, against the audit's suggestion: 50
  selectors in `09-console.css` are not scoped to `body.console` (`.btn`, `.badge`,
  `.bg-white` and other Bootstrap overrides), so a student page skipping it would lose them.
- Editing a part without running the build script means the change never reaches a browser.
  `CssBundleTest` fails in that case, which is the only thing that would notice.
- Incidental find, not fixed: that SVG noise filter reads `filter='url(%23n%23)'` — a
  trailing `%23` that makes it reference `#n#` — and has a doubled quote in
  `stitchTiles='stitch''`. The texture is probably not rendering. Cosmetic, and unverifiable
  without a browser.

### `6acfee9` — Serve Bootstrap and Archivo from our own origin
**Date:** 2026-09-10 · **Scope:** 8 files · **Deployed:** yes

**What changed**
- Bootstrap 5.3.3 vendored from the same jsDelivr build; Archivo copied from what Google
  Fonts served, `unicode-range` and all.
- All `preconnect` hints removed along with the origins they pointed at.
- CSP dropped `cdn.jsdelivr.net`, `fonts.googleapis.com` and `fonts.gstatic.com`.

**Why**
- A first visit touched four origins, two of them returning render-blocking stylesheets.
  Three DNS + TCP + TLS handshakes before first paint, to hosts never spoken to. On campus
  networks a handshake is not free.
- It also puts them behind the service worker's cache-first handler, which deliberately
  leaves third-party requests alone — so these were the only assets a returning student on a
  dead connection could not get.

**Verified by**
- 488 tests. Production `/login` contains **zero** third-party URLs.
- Every asset fetched from a running instance and confirmed 200 with the expected size.
- Google serves Archivo as one variable file for all four weights, confirmed by identical
  URL hashes across the four `@font-face` blocks — so self-hosting costs 34,928 bytes, not
  four files of that size.

**Watch out for**
- Vietnamese was dropped from the Archivo subsets; nothing here renders it and an uncovered
  character falls back rather than failing.
- The CSP test that pinned those CDN entries now asserts their **absence**. Re-adding a CDN
  link means deliberately editing that test, which is the intent.
- CSP is still report-only (`app.security.csp-enforce` defaults false), so the tightening
  cannot break production; it only narrows what gets reported.

### `b78ee29` — Actually subset the icon font: 377KB to 9KB
**Date:** 2026-09-10 · **Scope:** 4 files · **Deployed:** yes

**What changed**
- The icon font shipped with **5,514 outlined glyphs for the 64 icons this app draws**. It
  is now 88 glyphs and 9,104 bytes, down from 377,088.
- `scripts/subset-icon-font.py` fixed and made self-verifying.
- `IconGlyphCoverageTest` gained a file-size ceiling.

**Why**
- It was 80% of everything a student downloaded on a first visit — roughly three seconds on
  a 1 Mbps campus link, for icons.
- The script existed and ran without error. `populate(glyphs=..., text=...)` was the bug:
  fontTools walks GSUB from the glyphs it is handed, so the letters plus layout closure
  re-added every ligature they could form. The axis pinning that followed genuinely cut
  3.98MB to 377KB, and that number read as success.

**Verified by**
- All 64 icon names shaped through **HarfBuzz** against the original font and compared
  glyph-for-glyph: 64/64 identical. Repeated on the bytes fetched from a running instance,
  and again against production, which serves 9,104 bytes.
- The size guard was confirmed by restoring the 377KB font and watching the test fail.
- 488 tests.

**Watch out for**
- **A near-miss worth keeping.** An earlier attempt kept only `liga,dlig,calt,rlig` and
  dropped `rclt`, which disambiguates a name that prefixes another. This manifest has two
  such pairs, `check`/`check_circle` and `person`/`person_add`. The script's own docstring
  had warned about exactly this. The shaping check caught it; reasoning about the spec would
  not have. Keep every layout feature.
- My first shaping harness reported all 64 broken. The control — original against itself —
  failed identically, which is what showed the harness was wrong rather than the font:
  HarfBuzz cannot read woff2, so both fonts must be decompressed to TTF first. **Run the
  control before believing a red result.**
- Regenerating needs the full font, which is not in the repo. Download the current Material
  Symbols Outlined variable woff2 from Google Fonts and pass it as the first argument.

### `4a7f895` — Correct the Android doc's URLs and release-signing status
**Date:** 2026-09-10 · **Scope:** 1 file · **Deployed:** n-a (docs only)

**What changed**
- `docs/android-apps.md` named `bitesite-app.azurewebsites.net` as both apps' remote URL;
  the Capacitor configs actually point at `app.bitesite.in` and `outlet.bitesite.in/canteen`.
- The notes listed release signing and signed AABs as "Phase 2 (not yet done)". Both are
  done. Play CI and the Console listings genuinely are not, so the note says that instead.

**Why**
- The signing line was actively misleading while planning Play Store work: it said the
  hardest prerequisite was outstanding when it was already finished.

**Verified by**
- Read against the real files, not from memory: both `capacitor.config.json` files for the
  URLs, and `jarsigner -verify` on both AABs (`jar verified` for each) for the signing claim.
  Keystores confirmed present and gitignored.

**Watch out for**
- Nothing. Documentation only, no code path touched.

### `18a8ed9` — Put the whole app on Indian time, not the server's
**Date:** 2026-09-10 · **Scope:** 12 files · **Deployed:** yes

**What changed**
- New `BusinessClock` (`app.timezone`, default `Asia/Kolkata`, override `APP_TIMEZONE`).
- Student greeting now reads the Indian hour. This was the reported symptom.
- **Promo codes**: `valid_from`/`valid_until` are wall-clock times an admin typed thinking in
  IST, previously judged against a UTC now. A code set to end 23:59 kept working until 05:29
  the next morning.
- **Analytics** and **settlements**: "today" resolved to yesterday until 05:30 IST.
- OTP expiry, privacy-export timestamp and API error timestamps moved onto the same clock.

**Why**
- Reported as "Good afternoon" at 18:19 in India. The greeting was not the bug; `LocalTime.now()`
  reads the JVM default zone and Azure runs containers in UTC, so every time-of-day and
  day-boundary decision in the app was five and a half hours behind its users.
- OTP was already self-consistent (it wrote and read `expires_at` the same way) but stored
  those rows 5.5h behind every other timestamp in a database whose own clock is IST.

**Verified by**
- 487 tests pass **twice**: once in the machine's own zone (IST) and once with the JVM forced
  to UTC, which reproduces the production condition that caused the bug.
- `GreetingTest` pins the exact reported instant: at `2026-09-10T12:49:00Z` it asserts
  "Good evening" in Asia/Kolkata and "Good afternoon" in UTC — the bug and the fix in one
  assertion — plus the boundaries either side of 12:00 and 17:00.
- `BusinessClockTest` asserts hour 18 vs 12 at that same instant, that the date rolls at
  Indian midnight rather than 05:30, and that a bad timezone fails at startup.
- Every `LocalDate/LocalTime/LocalDateTime.now()` in `src/main/java` was enumerated by grep
  and either moved or deliberately left; none remain outside `BusinessClock`.

**Deployment**
- Pushed with `4a7f895` as a fast-forward (`03feedb..4a7f895`). Both workflows succeeded.
- Production healthy: `/actuator/health` 200 `{"status":"UP"}` and `/login` 200 across 6
  consecutive samples on `app.bitesite.in`, no blips this time.
- At the time of writing IST is 19:32 (UTC 14:02), so the greeting should now read "Good
  evening"; before the fix the same moment produced "Good afternoon". **Not confirmed by me** —
  the student menu is behind login.

**Watch out for**
- **The JVM default zone is deliberately unchanged.** The JDBC connection is pinned to
  `serverTimezone=UTC` and `OrderDao` documents date boundaries that depend on it, so moving
  the process zone would have a much wider blast radius than fixing each decision explicitly.
  Do not "simplify" this later by setting `TZ` on the web app.
- **In-flight OTPs broke across this deploy.** Codes issued in the ten minutes before restart
  were written on the old clock and read on the new one, so they read as expired. Affected
  users request a new code. One-off, not ongoing.
- `RateLimiter` still uses `Instant.now()` and should stay that way: an instant carries no
  zone and the window is a duration, so it was never affected.
- `AnalyticsServiceTest` and `OtpServiceTest` were comparing against the test runner's own
  clock and would have failed on a UTC CI machine for the first 5.5 hours of every day. Both
  now run on a pinned clock. That flake pre-dated this commit in `OtpServiceTest`.
- `application.yml` was deliberately not touched, so the default lives in the `@Value`
  annotation. This avoided entangling the change with an unrelated uncommitted gzip fix in
  that file. Worth moving into the yml with a comment once that lands.

### `03feedb` — Start the cancel window when the student is told, not when the money lands
**Date:** 2026-09-10 · **Scope:** 7 files · **Deployed:** yes

**What changed**
- The student's cancellation window now runs from the moment they are shown their payment
  confirmation, not from the moment Razorpay captured the money.
- New column `orders.cancel_window_starts_at` (migration `V31`). Both halves of the window
  — the student's cancel check and the kitchen's queue — read
  `COALESCE(cancel_window_starts_at, paid_at)`.
- Set on the browser callback in `CheckoutController`, not in `confirmPayment`.
- Set-once, and only while the order is still hidden from the kitchen.

**Why**
- Reported from a real order: the countdown was already part spent by the time the order
  screen rendered. Razorpay confirms twice — a server-to-server `payment.captured` webhook
  and the student's own browser callback — and the webhook nearly always wins, because it
  is one server calling another while the student is still on Razorpay's success screen.
  Measuring from `paid_at` therefore started the clock before the student saw anything. On
  a slow return the entire window could elapse before the button was ever visible.
- A separate column rather than moving `paid_at`, because `paid_at` is a money fact that
  reconciliation and settlement depend on. Rewriting it to a later time to fix a countdown
  would corrupt the answer to "when were we actually paid".
- Both halves had to move together. The moment they disagree there is a stretch in which an
  order is cancellable and being cooked at the same time.

**Verified by**
- 481 tests pass on JDK 21 (was 476; +4 flow, +1 unit).
- The reported behaviour is now a test: at 12s past capture the countdown reads 7-8s, and
  once the confirmation fires it reads a full 19-20s with the order still hidden from the
  kitchen.
- Also tested: set-once (a second callback is a no-op); an order already on the outlet queue
  is never pulled back off it; a student who never returns still falls back to `paid_at` and
  the kitchen still gets the order.
- Mutation-checked rather than trusted: reverting the anchor to plain `paid_at` failed
  exactly `theWindowRunsFromTheConfirmationTheStudentSawNotFromCapture` and nothing else.
  Reverted, all three `COALESCE` sites confirmed present, 12 tests green.
- `V31` applied to the test database with `success = 1`; column confirmed present and
  nullable via `SHOW COLUMNS`.
- Refund path confirmed working end to end by the user on a real paid order, which closes
  the item left open under `9bf32f6`. Reported, not observed by me.

**Deployment**
- Pushed to `master` as a fast-forward (`9bf32f6..03feedb`). Both GitHub Actions workflows
  completed successfully: CI, and Build and deploy JAR app to Azure Web App.
- Production is up: `/actuator/health` returned 200 `{"status":"UP"}` and `/login` 200 across
  8 samples over ~2.5 minutes. One sample returned no HTTP code at all — a transient
  connection failure, recovered on the next poll, consistent with this app's Burstable-tier
  behaviour rather than with this change.
- **The migration is inferred to have applied, not directly observed.** Flyway runs during
  context initialisation, so a failed `V31` would abort startup and nothing would serve.
  Both endpoints serving 200 therefore means Flyway completed. The production database is
  not reachable from this machine, so the column was not queried there directly.
- The startup log was not read: `az webapp log download` lags roughly 40 minutes on this
  app and would return the previous boot.

**Watch out for**
- **This one carries a schema migration**, unlike `9bf32f6`. Flyway runs before traffic is
  served, so the ordering is correct automatically. A rollback is safe: the previous code
  never references `cancel_window_starts_at`, so the column would simply sit unused.
- `ALTER TABLE orders ADD COLUMN` on a nullable column is an INSTANT operation in MySQL 8,
  so it should not lock at current order volume. Not measured against production row counts.
- The local development database picks up `V31` the next time the app is run.
- **Confirmed on a real order** (2026-09-10, after deploy): the countdown starts at the full
  window on a live payment. Reported by the user, not observed by me. This closes the last
  open verification item on the cancellation feature — every path is now either tested or
  confirmed in production.
- Behaviour worth knowing: if a student's browser returns *after* the window already
  elapsed, the anchor is deliberately not set and they get no window. They were too late
  under the old behaviour as well; this makes it deterministic rather than a race.

### `9bf32f6` — Let an admin set how long a student can undo an order
**Date:** 2026-09-10 · **Scope:** 9 files · **Deployed:** yes

**What changed**
- The student cancellation window is no longer compiled in at 20 seconds. It is a
  platform setting, edited at `/admin/orders/settings` and reachable from a button on
  the All orders screen.
- Read fresh on every order, so a change takes effect on the next order rather than
  the next restart.
- Clamped to 0-300 seconds. Zero switches self-cancellation off entirely: no cancel
  button, and the kitchen sees every order the moment it is paid for.
- Gated to `FULL_ADMIN`, not the `OPS_SCOPE` the order list uses. Audited before and
  after, like the billing terms.
- Fixed a stranded javadoc: the block describing `cancelOwnOrder` had been left above
  `selfCancelSecondsLeft` by `f3d4665`, the commit that introduced it.

**Why**
- 20 seconds was a guess made when the feature shipped, and the right number is an
  operational question that varies by canteen and by how busy a counter is. Changing a
  guess should not require a redeploy.
- The bound matters more than the default. This number is also how long a paid order is
  invisible to the counter, so an unbounded field would let a stray digit strand a
  student waiting on food nobody had been told to cook.

**Verified by**
- 476 tests pass on JDK 21. The toolchain here defaults to JDK 26, which this project
  warns breaks Lombok silently; the run was done with JDK 21 explicitly.
- Full Spring context boots with the new controller and the new `OrderService`
  dependency, which rules out a duplicate route mapping, a missing bean and a circular
  dependency.
- New `OrderCancelWindowFlowTest` drives the real controller against real MySQL: the
  form renders the window actually in force; a `TECH_MANAGER` gets 200 on the order list
  but 403 on both GET and POST here; a saved 45 reaches `OrderService`; 99999 clamps to
  300; `"twenty"` is rejected as a form error without disturbing the live setting.
- The safety property is now tested in SQL, where it lives. With `paid_at` backdated 25
  seconds, cancellable and kitchen-visible come out as exact complements at 30s, at 20s,
  and exactly on the boundary at 25s.
- That boundary test was mutation-checked rather than trusted: flipping `>=` to `>` in
  the kitchen queue made it fail at the boundary assertion. The mutation was reverted and
  `git diff` on `OrderDaoImpl.java` confirmed empty.

**Deployment**
- Pushed to `master` as a fast-forward (`8fc4e27..9bf32f6`). Both GitHub Actions
  workflows completed successfully: CI, and Build and deploy JAR app to Azure Web App.
- Production came back up. `/actuator/health` returned 500 for roughly the first 60
  seconds after the deploy and then settled to 200 `{"status":"UP"}`; `/login` served 200
  throughout. That early 500 matches the known Burstable-tier MySQL boot lag on this app
  rather than anything in this commit.
- Not confirmed in production: the new route responding as designed. Every unauthenticated
  `/admin/**` request redirects to `/login`, including a deliberately nonexistent control
  path, so an unauthenticated probe cannot distinguish a registered route from a missing
  one. Route registration is verified locally instead, by MockMvc against the full context.
- The startup log was not read. `az webapp log download` on this app lags roughly 40
  minutes, so it would return the previous boot; presenting it as evidence for this deploy
  would be wrong.

**Watch out for**
- **No migration.** `platform_settings` already existed. An absent row reads as 20, so
  deploying this changes nobody's behaviour until somebody edits the setting.
- **Changing it moves orders already in flight.** Shortening the window can close it on
  an order still counting down and hand that order straight to the kitchen; lengthening
  it can pull a paid order back out of the outlet queue. An order already `PREPARING` is
  unaffected either way — the state machine still forbids that transition, so no refund
  can be issued for food on the grill. This is stated on the admin screen itself.
- **Both end-to-end checks have since been done by the user** (2026-09-10, reported not
  observed). The refund lands in Razorpay correctly. The live countdown check *found a bug*:
  the window was being measured from payment capture rather than from the confirmation the
  student is shown, so it started part-spent. Fixed in `03feedb`, which supersedes this
  entry's countdown behaviour. The refund path itself was untouched by either commit:
  `cancelOwnOrder` still delegates to `cancelOrder`, which still refunds before touching
  the database.
- One extra `SELECT` against a ~15-row table per kitchen-queue poll and per order screen.
  No caching was added, matching `BillingService`, which reads its settings the same way
  on the checkout path.
- `LEDGER.md` remains untracked, as it has been for every entry in it. This entry was not
  committed with the code.

### `8fc4e27` — Hide duplicate top bar and restore prominent chart bars
**Date:** 2026-09-10 · **Scope:** 3 files · **Deployed:** yes

**What changed**
- Fixed chart bars collapsing to zero height on the analytics dashboard.
- Removed the duplicate top strip that appeared alongside the new sidebar.

**Why**
- Thymeleaf's `th:style` was stripping the inline `width`, `background` and `border`
  the bars depended on, so the chart rendered as a flat line.

**Verified by**
- Re-deployed to Azure and checked the live dashboard.

**Watch out for**
- The fix moved bar styling into dedicated CSS classes in `09-console.css`
  (`.analytics-bar`, `.analytics-bar-track`, `.analytics-baseline`). Inline styles on
  these elements will not survive Thymeleaf; use the classes.

### `a20898d` — Enhance analytics visual charts and daily sequence
**Date:** 2026-09-10 · **Scope:** 3 files · **Deployed:** yes

**What changed**
- Enforced a minimum 25% height floor in `AnalyticsDaoImpl` so active days and rush
  hours are always visible rather than rendering as slivers.
- Filled empty calendar days with baseline markers so the full date progression reads
  continuously instead of jumping over quiet days.
- Deduplicated slow-moving dishes from the bestseller list, which overlapped on small
  menus.

**Watch out for**
- The 25% floor is a *display* minimum. It does not change the underlying figures, but
  it does mean bar height is not linearly proportional at the low end. Read the labels,
  not the bars, for small numbers.

### `db8bd5e` — Add college & canteen analytics dashboard gated to full admin
**Date:** 2026-09-10 · **Scope:** 11 files, +1,234 lines · **Deployed:** yes

**What changed**
- New dashboard at `/admin/analytics`.
- Filters: Today, Yesterday, Last 7 Days, Last 30 Days, Month to Date, and a custom
  date range. Filter by all colleges or one; filter by canteen, narrowed in the browser
  to the chosen college.
- KPI scorecard: Food GMV, platform cut (commission + fees), orders and AOV, active
  customers and repeat rate, fulfilment rate with cancellations and lost GMV, and promo
  redemptions split by platform-funded vs canteen-funded.
- Visualisations: daily growth trajectory, 24-hour kitchen rush heatmap (12:00–14:00
  highlighted for staffing), canteen leaderboard, and a menu velocity matrix showing
  the top 10 sellers against slow movers.

**Why**
- The commercial data existed after the billing work but nothing surfaced it. A
  platform cut you cannot see per college is a number nobody can act on.

**Verified by**
- Gating confirmed: `PortalGuard.requireScope(..., StaffScope.FULL_ADMIN)` at
  `AnalyticsController:49`, mapped at `/admin/analytics`. Tech managers, canteen staff
  and students receive 403.

**Watch out for**
- 6–7 aggregate queries per page load, filtering on `token_day`.
  `idx_orders_token_day` exists so the shape is right, but **no query plan has been
  checked at volume**. This is the most likely place for a slow page as order history
  grows. See [OPTIMISATION.md](OPTIMISATION.md), "Not yet measured".

### `392729c` — Ship billing, promo codes, and admin sidebar
**Date:** 2026-09-10 · **Scope:** 63 files, +4,347 lines · **Deployed:** yes

**What changed**
- **Billing spine.** Platform commission per canteen, platform fee, GST-inclusive tax
  extraction, and a real on-page invoice.
- **Promo codes.** Flat and percentage discounts with ceilings, minimums, scope, date
  windows, total and per-student caps — and the funding rule that decides who pays.
- **Settlements.** Per-canteen payout ledger with period filters.
- **Admin sidebar.** The 12+ crowded horizontal links became a persistent
  neo-brutalist left sidebar, grouped into Platform / Money / Oversight / Accounts.
  Below 992px it collapses into the existing slide-out drawer. Standardised
  `body.console console--has-sidebar` across templates to stop the top strip rendering
  twice.

**Why**
- The commercial terms had to be snapshotted onto every order so that changing a rate
  tomorrow can never restate a bill a student already paid or a payout a canteen is
  already owed.
- The funding rule is the one that matters: a **platform**-funded discount leaves the
  canteen settled in full and comes out of the platform's margin; a **canteen**-funded
  one lowers what the canteen sold for and the commission falls with it. Getting it
  backwards makes canteens quietly pay for the platform's marketing.

**Verified by**
- 461 tests passing (up from 440).
- Driven end to end against a real database: platform-funded order (food ₹340, −₹20)
  produced commission ₹10.20 = 3% of 340; canteen-funded (food ₹420, −₹20) produced
  ₹12.00 = 3% of 400.
- Settlement report: food ₹740, platform-funded −₹20, commission ₹22.20, net payable
  ₹717.80. Books balance to the rupee against ₹720 collected.
- Invoice: ₹304.76 + ₹7.62 + ₹7.62 = ₹320.00 — tax computed on the discounted amount,
  not the gross.
- All seven refusal paths exercised live (nonexistent, inactive, expired, wrong
  college, budget spent, per-user cap, below minimum), each with its own message.
- CI and the Azure `bitesite-app` deploy both watched through to success.

**Watch out for**
- **Two migrations:** `V29__billing_and_settlement.sql` and `V30__promo_codes.sql`.
  Every seeded setting is inert — commission 0, fee 0, charging off, GST off, tips off
  — so the platform charges nothing until somebody deliberately turns it on.
- **Commission inheritance:** set per canteen under Colleges → [College] → Canteens →
  Commercial terms. A typed percentage is a locked-in negotiated rate; **blank** tracks
  the platform default. A negotiated 0 is not the same as blank and will survive the
  default moving.
- Fixed in passing: the `payments` row was recording the food-only total while the
  gateway was charged the collected total. With a tip or fee active the Razorpay widget
  would have shown a different number than the order it was paying. Never shipped.
- The `campus-groups` branch still carries a `V28` that collides with master's, and
  V29/V30 now exist. It needs renumbering before it can be merged.

### `16cbe1d` — Put every account on the platform in one place
**Date:** 2026-09-10 · **Scope:** see commit · **Deployed:** yes

**What changed**
- Directory at `/admin/accounts` covering students, canteen staff and platform accounts,
  with search and filters by role, college and status.

---

## 2026-09-09

### `1417711` — Build a canteen's menu from a spreadsheet
CSV menu import, handling BOM, CRLF and quoted commas. Format documented on the import
screen itself.

### `61cc598` — Make a payment visible, a password change loud, and a probe possible
Added payment logging. Before this a **successful payment wrote no log line at all**,
so silence in the logs was never evidence that payments were failing — a trap that
produced one wrong conclusion during debugging.

### `e67b564` — Give a reset code somewhere to be typed
Reset codes were being sent to outlet users with no screen to enter them on.

### `fb0a752` — Add an admin tier, and let somebody switch a canteen account off
ADMIN role between SUPER_ADMIN and the rest. Only a super admin can assign or change
admin roles; an admin can assign and change every other role but not admin. Escalation
by subtraction is blocked. Also fixed canteen staff accounts that could not be deleted
or disabled.

### `1e6c2ef` — Stop a tap looking like nothing happened
Loading states across the app, with a double-tap guard on the money path.

### `ee44798` — Filter the long lists in the staff consoles
Client-side filters where the list is complete in the page; server-side kept where it
is paginated.

### `f7ffd60` — Let an admin rename or delete a college onboarded by mistake
College rename with unique-collision handling, and deletion behind a confirmation
screen with a full FK-ordered cascade.

**Watch out for**
- Deletion refuses if the college has orders or students. The cascade order matters:
  users and everything hanging off them must go **before** outlets, because
  `users.outlet_id` references outlets. Caught only by a real-database test, not mocks.
- Found and fixed a latent bug in passing: deleting a canteen with any category failed
  on `fk_categories_outlet`.

---

## 2026-09-08

### `8172cf5` · `85859f3` · `471a004` · `631e638` · `e5f5ca1` — Performance work
Full detail in [performanceaudit.txt](performanceaudit.txt) and
[OPTIMISATION.md](OPTIMISATION.md). Summary:

- **Compression enabled** — student menu 39,852 → 4,525 bytes on the wire (−89%).
- **Cart stopped writing a BLOB on every page view** — anonymous page 11.3 → 5.3
  statements, `/login` p50 43ms → 17ms.
- **Cart moved out of `@SessionScope`** — student render 22.3 → 18.3 statements.
- **Template comments stripped from output** — menu 54,640 → 39,852 bytes, with not one
  word removed from the source.

---

## Uncommitted work in progress

Kept here so nothing is lost between sessions. Move each into a dated entry when it
lands.

| File | What it is |
|---|---|
| `CLAUDE.md` | The Work Ledger section that defines these rules. |
| `OPTIMISATION.md` | Current optimisation status: what is done, what is left in priority order, and what has not been measured. |
| `LEDGER.md` | This file. |


## Reported, not independently verified

Work carried out in another session and recorded here on report. What was confirmed
against this repository is noted; the rest is taken as given.

### Android apps — Play Store readiness
**Date:** 2026-09-10 · **Deployed:** n-a (not submitted)

**Reported**
- Capacitor wrapper apps audited: `android-student` (`in.bitesite.app`) and
  `android-outlet` (`in.bitesite.outlet`).
- Release signing and Firebase push configuration already in place.
- A Java 26 build failure was resolved by pointing `JAVA_HOME` at JDK 21, after which
  `./gradlew bundleRelease` produced signed bundles.

**Confirmed against the repo**
- `android-student/android/keystore.properties` and
  `android-outlet/android/keystore.properties` both exist.
- Signed bundles exist at
  `android-{student,outlet}/android/app/build/outputs/bundle/release/app-release.aab`.

**Watch out for**
- **JDK 21 is required.** Newer JDKs break Lombok silently on the server side and broke
  this Gradle build. Set `JAVA_HOME` before building either app.
- Build outputs under `app/build/` are not committed and will not survive a clean. The
  bundle has to be rebuilt to be re-uploaded.
- Neither app has been submitted to Google Play. Readiness is not release.
