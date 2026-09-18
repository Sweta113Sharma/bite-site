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

## 2026-09-18

### `4ac6a4e` — Add a long-running soak test of a whole lunch service over real HTTP
**Date:** 2026-09-18 · **Scope:** 2 files · **Deployed:** n-a (test only)

**What changed**
- `LongRunningOrderSoakTest` (opt-in, `-Dstress=true`, ~17 min). Students, staff and an
  admin over real HTTP; every Razorpay confirmation ordering; cancellations, item removals,
  promo and daily caps; audit of every order and rupee; MySQL snapshot when the pool queues.
- One line in `CLAUDE.md` on how to run it.

**Why**
- The existing stress test is a seconds-long service-layer burst. Nothing exercised sessions
  in MySQL, the pool over time, CSRF under load, webhook/callback races or staff collisions.

**Verified by**
- Three full runs (1,170 arrivals each, 3 colleges × 3 canteens) plus a smoke run, reports in
  `target/stress-reports/`. Across ~125k requests: 0 5xx, 0 403, refunds sent = refunds owed,
  promo caps exact at 40, daily caps exact at 30, no duplicate tokens or live pickup codes, no
  cross-college rows. Run 3: 110–119 completed per canteen, p95 23–40ms, pool peak 4/10.
- Normal suite unaffected: the test skips without `-Dstress=true` (641 run, 0 failures, 5 skipped at the time).

**Watch out for**
- **It fails today, on purpose:** 11 (run 2) and 6 (run 3) orders reached READY_FOR_PICKUP
  twice. `OrderService.advanceStatus` checks then updates with no status predicate, so two staff
  taps both win and the pickup code is re-issued. Not fixed.
- Also found, not fixed: READY is committed before the pickup code (5s gap seen under load; a
  failure there strands the order with no code), and `SavedCartDaoImpl.save` deadlocks
  (19 and 10 per run: DELETE-then-INSERT gap locks under REPEATABLE READ).
- One 30s pool exhaustion in run 2 (165 waiting, 18.4s max). Cause not found; the session-cleanup
  theory was tested in run 3 (500 sessions deleted under peak load) and refuted.
- Leaves its rows in `bitesite_test_db`, like the other stress tests. Local only; never point it at production.

### `c3aa66b` — Count only wrong passwords toward the login limit
**Date:** 2026-09-18 · **Scope:** 4 files · **Deployed:** yes (2026-09-18, run 35325215254; new cropper confirmed served from app.bitesite.in)

**What changed**
- `LoginRateLimitFilter` now refuses only when a budget of *failures* is spent: address +
  account (10 per 5 min, email SHA-256-hashed in the key) or address alone (100 per 5 min).
- `LoginFailureHandler` records a failure only for `BadCredentialsException`.
- `RateLimiter.isBlocked`: read-only check, for limits that count some outcomes only.
- New `LoginRateLimitTest` (3 cases).

**Why**
- Every POST /login counted, successes included, at 10 per 5 min per IP. The 11th student on
  one campus network inside 5 minutes was refused with the right password (reproduced locally).
- Unverified: whether production sees real client IPs at all. The read-only query on
  `rate_limit_window` could not run (Azure MySQL firewall refuses this machine; the session's
  permission rules also blocked it). Locally, with Azure detection env vars, Spring did honour
  `X-Forwarded-For` from a private-range peer. If production does not, the old limit was
  10 logins per 5 min for the whole platform; the new one is 100 failures.

**Verified by**
- `LoginRateLimitTest`: 25 correct logins from one address all pass; 10 wrong passwords block
  that account from that address only (other students there, and the victim elsewhere, get in);
  100 failures from one address block it. Run against the old code: fails at student 11.
- `mvn test` on JDK 21: 644 run, 0 failures, 5 skipped.

**Watch out for**
- Existing `login:<ip>` rows in production go unused and age out via `evictStale`.
- Registration and password reset are still counted per IP on every attempt; same NAT exposure, lower traffic. Not changed.

### `73bebcb` — Let canteens picture the All Dishes chip, and crop every upload first
**Date:** 2026-09-18 · **Scope:** 20 files · **Deployed:** yes (2026-09-18, run 35325215254; new cropper confirmed served from app.bitesite.in)

**What changed**
- **All Dishes chip gets a picture.** `/canteen/categories` has an "All Dishes" card at the
  top (upload / remove). `/admin/category-images` has an "All Dishes" section setting the
  platform default. Students see the canteen's own, else the platform's, else the bundled
  ramen, exactly as before for anyone who uploads nothing.
- **Cropper on every image upload.** Category images (canteen and admin), menu item photos
  and both logo forms open `image-crop.js` when a file is picked. Square for chips and logos,
  4:3 for menu photos. Drag, pinch, wheel, −/+ buttons, slider or arrow keys; Rotate, Fit
  whole (leaves see-through edges), Reset; a preview at the size the app draws it. On
  category rows "Save image" submits at once; Cancel/Escape leaves the current picture alone.
- **Compression before upload.** The crop is encoded at exactly the server's kept size as
  lossy WebP (JPEG, or PNG only if transparent, where the browser cannot encode WebP).
  Menu form shows the saving, e.g. "Cropped: 1.4MB → 154KB".
- **Category images stored smaller:** `ImageUploadProcessor.Kind.CATEGORY_IMAGE` 400px /
  0.85 → 256px / 0.75.
- New migration `V40__all_dishes_image.sql` (nullable `outlets.all_dishes_image_path`); new
  platform_settings key `category_image.all_dishes`.

**Why**
- The user could not set a graphic for "All Dishes": it is not a category, so the V39 tables
  had nowhere to store it and the template hard-coded `/img/food/food_ramen.png`.
- Pictures are drawn in fixed shapes (58px square chips, 4:3 dish cards), so any other shape
  was centre-cropped by `object-fit` with no say from the uploader. The logo cropper
  (68f8062) existed but only logos used it, and it had no pinch, no context outside the
  frame, and a canvas that rendered at 1x on retina screens.
- A phone photo was being posted whole (2–8MB on a campus connection) and thrown away
  server-side, and anything over 5MB was refused outright.

**Verified by**
- `mvn test` on JDK 21: 640 run, 0 failures, 4 skipped (the opt-in stress/profile tests).
  Includes 9 new `CategoryImageServiceTest` cases (fallback order, blank setting = unset,
  cache invalidated on set/clear, cross-tenant outlet id → not found and nothing stored) and
  the DB-backed security tests, which applied V40 to `bitesite_test_db` cleanly.
- V40 applied to local `bitesite_db` on boot (v39 → v40).
- Driven in Playwright's Chromium against the local app, desktop 1280×900 and phone 390×844
  (DPR 3, touch): All Dishes crop + save stored a 256×256 WebP of 11,136 bytes; wheel zoom,
  drag, −/+ buttons, Fit whole, Reset all moved the slider as expected; Escape cleared the
  input and restored the row preview, and re-picking reopened the cropper; menu photo came
  out 1600×1200 WebP, 1.4MB → 154KB, form not auto-submitted; on the phone the dialog fit
  with nothing overflowing and a CDP two-finger pinch took the slider 152 → 731; admin
  "All Dishes" default set via the cropper.
- Fallback chain driven through the UI: own set → student chip showed it; canteen removed
  it → chip showed the platform default and the card said "Using the platform image"; admin
  removed that → chip showed `/img/food/food_ramen.png`.
- Server compression measured with the real `scaleToFit` + WebP writer: ramen illustration
  24,212 → 15,558 bytes, a photo 10,088 → 4,440 bytes (400/0.85 → 256/0.75); both inspected
  by eye, no visible ringing or blocking.
- Local dev data restored afterwards: test uploads removed through the UI, the leftover
  NULL settings row and the four orphaned test files deleted.

**Watch out for**
- **Migration V40** runs on the next deploy. Additive and nullable; nothing reads the column
  until this code is live, so order does not matter.
- **Only Chromium was driven.** Safari (no WebP encoding from canvas: falls to JPEG/PNG path,
  never exercised) and a real Android device / the Capacitor WebView are unverified.
- On production, students already receive a Cloudinary-derived 160px chip
  (`c_limit,f_auto,q_auto`), so the stored-size cut mostly saves storage and upload time,
  not student bandwidth. On local disk storage it is also what students download.
- Replacing an image still leaves the old file in storage (pre-existing, same as V39).
- In the Playwright runs the crop stage showed a focus ring on open, because the file was
  set without a click. After a real tap it should not match `:focus-visible`; not confirmed
  on a device.
- One Playwright run timed out loading the student menu before any change was made; two
  reruns and a standalone probe passed. Most likely the login rate limiter (10 POSTs per
  5 min per IP, `LoginRateLimitFilter`): later in the same session repeated test logins
  landed on `/login?error=ratelimit`, and a blocked student login leaves the menu request
  on the login page with no chips. Not confirmed for that run, which did not log its URL.
  Scripted checks should log in once and reuse the session.
- Static files are served under a content hash computed at startup, so editing JS/CSS in
  `target/classes` of a running app changes nothing the browser sees. Restart to test.

## 2026-09-17

### `e6e25e6` — Let a student fix the college they picked at signup
**Date:** 2026-09-17 · **Scope:** 11 files · **Deployed:** yes (2026-09-17 16:09 UTC, run 35244055838)

**What changed**
- A student can change their college from `/account/profile`, until their first order.
- An admin can change it from `/admin/accounts` with that guard lifted, and is told how
  many past orders the move strands before they confirm.
- New `UserDao.updateTenant`, `OrderDao.countByUserId`, and three methods on `UserService`.
- `bootstrap.min.css` regenerated: the new markup uses `mt-5` and `pt-4`, which were not in
  the subset.

**Why**
- Nothing verifies which college a student belongs to. `RegistrationController` only checks
  the chosen college is ACTIVE, `rollNo` is free text with no validation, and there is no
  email-domain or invite check anywhere. So the college is a self-declared guess.
- `uq_users_email UNIQUE (email)` then makes that guess permanent: the same address cannot
  register again at the right college. There was no self-service fix and no admin fix. Three
  colleges now sit on that dropdown and two of them are engineering institutes, which is not
  a hard mistake to make.
- This is the small half of a larger question — whether students should be able to browse and
  order across colleges at all. That was planned in full and **deliberately not built**: all
  three live colleges are in different cities (Kanpur, Pune, Greater Noida), so a switcher has
  no user today, while the change would run through 44 call sites in the money path. See the
  plan file for the design if it becomes worth doing.

**Verified by**
- Driven end to end against a local server with two real students, not stubs.
  `student@second.local` (0 orders) moved college 2 → 3: the row changed, the audit row
  landed with before=2/after=3, the cart emptied, the session survived (200, not a bounce to
  login), and the picker afterwards read "niet greater noida".
- **The block is server-side, not just a hidden form.** `student@demo.local` (26 orders) has
  no form on the page; posting to the endpoint anyway left `tenant_id` at 1 and returned the
  refusal message.
- **A staff account cannot be moved.** Posting `/admin/accounts/3/college` for a
  CANTEEN_MANAGER left their tenant unchanged and returned "Only a student account's college
  can be changed here." The Move control is not drawn on staff rows either, confirmed by
  screenshot: it appears on the USER row and on neither of the two staff rows.
- **The admin warning is true, not reassuring.** After moving a student with 26 orders, the
  orders were still 26 rows in the database — so the canteen's queue, settlement and GST are
  untouched — and 0 of them rendered on the student's own history page. That is exactly what
  the confirmation says will happen.
- Sessions behave as intended both ways: self-service left 1 of 3 sessions alive (their own);
  the admin path left 0, and the student's old cookie then redirected to `/login`.
- The two staff-guard tests were proven to fail: deleting the guard turned both red, and only
  those two.
- 631 tests, exit 0. Both screens screenshotted at their real widths.

**Watch out for**
- **The order count is the whole safety argument.** `orders.tenant_id` is what puts an order
  in a canteen's queue, its daily settlement and its GST invoice (V29), so a move must never
  carry orders with it. Every student-side history read is tenant-scoped, so anything left
  behind is invisible to them afterwards. If history is ever made user-scoped, this guard
  becomes unnecessary and should go, not be worked around.
- `changeOwnCollege` spares the caller's session and revokes the rest; the admin path passes
  null and revokes all. A session holds a snapshot of the account taken at sign-in and nothing
  re-reads it per request, so a phone left signed in would otherwise keep ordering from the
  old college.
- The guards live in `UserService`, not in the two controllers, on purpose. A third caller
  that forgot one would be a tenant-isolation hole rather than a missing validation message.
- Audit rows are recorded against the college being **arrived at**, with both ids in the
  payload. The old college's trail keeps every row it had.
- No migration. Nothing was added to the schema.
- Not yet checked on a real phone, only at phone viewport.


### Deployment — `e6e25e6`
**Date:** 2026-09-17 16:09 UTC · **Run:** 35244055838 · **Outcome:** success

- CI (run 35244055892) green. Both workflows exit 0. No migration in this batch.
- **A better swap signal than a new route.** This change added no public URL, so the
  content hash on the vendored stylesheet was used instead: production emitted
  `bootstrap.min-8823397266bc3ee2c4205343c12d47fb.css` (the md5 of the file at HEAD~2) until
  the swap, then `ac55d472bfae3e84b7999ca5ea706817` (the md5 of the regenerated one). Exact,
  and it needs nothing added to the app to make it observable. Worth reusing.
- **Mid-swap the hashed stylesheet 404s, and briefly reads as 500.** During the window the
  page still emitted the old hash while that file was already gone, so every page on
  production was linking a stylesheet that did not resolve. It cleared on its own the moment
  the swap finished. Alarming to walk into and not a fault: do not go looking for a resource
  handler bug over it, and do not check a static asset for a minute or two after a green tick.
- Verified on the live host afterwards:
  - The hashed stylesheet serves 200, 49,474 bytes, **byte-identical** to the local file, and
    contains the `.mt-5` and `.pt-4` rules the regeneration was for.
  - `/account/profile` and `/account/profile/college` both 302 to `/login` while signed out,
    which is the correct answer for an authenticated route and proves the mapping exists.
  - All three portals 200, `/actuator/health/liveness` UP.
- **Not verified in production, deliberately.** The two screens are behind a student and an
  admin login, and checking them live would mean signing in as a real person's account. Both
  were driven end to end locally against real accounts instead, including the
  post-anyway bypass attempt and the staff-account refusal. What production confirms is that
  the code shipped and routes; what confirms the behaviour is the local run and 631 tests.

### `68f8062` — Let people square up a logo before it uploads
**Date:** 2026-09-17 · **Scope:** 6 files · **Deployed:** yes (2026-09-17 11:40 UTC, run 35216371318)

**What changed**
- Picking a canteen or college logo now opens a square frame you can drag and zoom before
  it saves.
- The college logo form gains a preview and the shared `.photo-dropzone` treatment; it was a
  bare file input before.
- New `static/js/image-crop.js`, opt-in per dropzone via `data-crop`.

**Why**
- Both logos are drawn as small squares — 28px in the navbar, 56px on the canteen picker,
  88px on the crest above it — so a wide or off-centre source was letterboxed and nobody had
  a say in the framing. On the college form there was not even a preview: you picked a file
  and found out after saving.
- Hand-written rather than a cropping library because the CSP permits scripts from this
  origin and Razorpay only, so a CDN is out, and this project has been removing vendored
  frontend dependencies rather than adding them.

**Verified by**
- Driven in a browser on both forms. A 900x300 image in three colour bands, panned left and
  zoomed to 1.4, stored as 512x512 showing the band it was moved toward and **none** of the
  one it was moved away from — so position and zoom were both honoured rather than a centre
  crop being assumed.
- On the canteen form: Cancel then Save left the logo NULL; Apply then Save stored a cropped
  one. Checked at phone width as well as desktop.
- **The EXIF question is settled, not assumed.** A 200x100 JPEG with red on the LEFT carrying
  EXIF orientation 6 ("rotate 90 clockwise") stored as a square with red on TOP — exactly one
  rotation. The browser applies orientation when decoding, the canvas export carries no EXIF
  tag, so the server's own rotation step is a no-op rather than a second rotation.
- 624 tests, exit 0.

**Watch out for**
- **The server is deliberately untouched.** The crop is written back into the original file
  input via `DataTransfer`, so the same multipart form posts as before. If `DataTransfer` is
  ever unavailable the script bails out and the original file uploads uncropped.
- Export is at exactly 512 to match `ImageUploadProcessor.Kind.LOGO`, so the server has no
  downscaling left to do. Changing one without the other means the crop gets resized after
  the fact.
- `FRAME_PX` in `image-crop.js` and the `.crop-dialog__frame` size in `05-shared.css` are the
  same number in two places; the pan/zoom maths is in those units.
- Output is PNG, not JPEG, because logos are often transparent and JPEG would flatten that to
  black.
- Menu item photos and category images are unchanged — crop is only on the two logos.

### `038a95b` — Add a public /install page for a QR code to point at
**Date:** 2026-09-17 · **Scope:** 5 files · **Deployed:** yes (2026-09-17 11:40 UTC, run 35216371318)

**What changed**
- New public `GET /install`: an add-to-home-screen landing page, meant to be the target of a
  printed QR code.
- `hasRole('USER')` moved off the `installPrompt` fragment definition and onto its include in
  the student navbar.
- New `[data-install-have-it]` and `[data-install-fallback]` hooks in `updateInstallButtons()`.

**Why**
- The app has had a complete install mechanism for a while, and none of it was reachable
  without signing in: the sheet is included from the student navbar and only four signed-in
  pages carry `data-install-auto`. There was no URL to print on a poster, which is aimed at
  exactly the person who has no account yet.
- Almost nothing new was needed. `fragments/head.html` loads `app.js` on every page with no
  security gate, so `initInstallPrompt()` already runs on public pages and wires the button,
  the auto-open and the sheet.

**Verified by**
- `/install` returns 200 while signed out and the sheet renders for an anonymous visitor.
- The gate move is a no-op elsewhere, checked both ways: the sheet is still present for a
  student on `/student/menu` and still absent for a canteen manager on `/canteen/queue`.
- With a simulated `beforeinstallprompt` the sheet auto-opens on the `prompt` variant and its
  Install button calls `prompt()` and closes; with an iPhone user agent it auto-opens on the
  `ios` variant; with localStorage marking it installed the button hides and the note shows;
  with none of those the fallback appears.
- 624 tests, exit 0.

**Watch out for**
- **The last mile cannot be verified from here and was not.** `beforeinstallprompt` does not
  fire under Playwright/CDP, and iOS Safari has no install API at all. The Android install
  dialog and the iPhone Share flow each need a real device scanning the code.
- A fourth state had to be added during the work: a browser that is neither Android-installable
  nor iPhone (Firefox on Android, say) was being shown a page about installing with no way to
  install and no explanation. It now gets browser-menu instructions, armed on a delay so it
  cannot flash before Android's event arrives.
- Caught by screenshot, not by reasoning: `bolt` is not in the subsetted icon font, so it
  rendered as the literal text "BOLT" on the page. Swapped for `home`. `IconGlyphCoverageTest`
  covers this and would have caught it at build time.
- The install sheet's subtitle lost "keeps you signed in", which is presumptuous for a reader
  with no account. That copy shows on the student pages too.


### Deployment — `038a95b`, `68f8062`
**Date:** 2026-09-17 11:40 UTC · **Run:** 35216371318 · **Outcome:** success

- CI (run 35216371319) green. Both workflows exit 0. The push carried three commits; the
  ledger commit `42c706a` is only the head, and `paths-ignore` is evaluated across the whole
  push, which is why a `.md` head commit still shows as the deploy's title.
- No migration in this batch. V39 was already applied on the 20:32 UTC deploy the day before,
  so none of the Burstable-tier startup risk applied here.
- The swap window held for a third time: the workflow reported success at 11:36:47 UTC and
  production did not serve the new build until roughly 165s later. `/install` is a route that
  did not exist before, so it was a clean signal — 302 to `/login` on the old build, 200 on
  the new one, with no ambiguity about whether a cached page was being read.
- Verified on the live host afterwards:
  - `/install` returns **200 while signed out**, 9,212 bytes, and carries all five hooks the
    page depends on (`data-install-auto`, `data-install-trigger`, `data-install-have-it`,
    `data-install-fallback`, `id="install-prompt"`).
  - The gate move did not leak the sheet: `id="install-prompt"` appears **0 times** on
    `/login` for all three portals and on `/register`.
  - `/js/image-crop.js` serves 8,898 bytes as `text/javascript`, and `crop-dialog__frame` is
    present in the deployed `app-bundle.css` — so the bundle regeneration shipped, not just
    the parts.
  - Cache headers are as intended either side of the split: `/install` is
    `no-cache, must-revalidate, private`, `image-crop.js` is `max-age=31536000, immutable`.
  - All three portals 200, `/actuator/health/liveness` UP.
- **Still unverified, and only a phone can settle it.** `beforeinstallprompt` does not fire
  under Playwright/CDP and iOS Safari has no install API, so the Android install dialog and
  the iPhone Share flow each need a real device scanning the code. Everything up to the point
  where the browser takes over is confirmed; the browser's own dialog is not.

### Deployment — `6e1b2cb`, `0c357ac`
**Date:** 2026-09-17 09:32 UTC · **Run:** 35205213350 · **Outcome:** success

- CI (run 35205213312) green before the deploy landed.
- No migration in this batch, so none of the Burstable-tier startup risk applied. App
  started in 162.4s.
- Verified afterwards: all three portals 200, liveness UP, new boot present in the
  container log at 09:32:46 UTC.
- Same swap window as the previous deploy: the workflow reports success roughly two and a
  half minutes before production actually serves the new build. Checking immediately after
  a green tick reads as "shipped nothing" and is not.


### `0c357ac` — Let an admin put a staff account on a canteen
**Date:** 2026-09-17 · **Scope:** 7 files · **Deployed:** yes (2026-09-17 09:32 UTC, run 35205213350)

**What changed**
- A staff account's canteen can now be set, or changed, from the college screen — a select
  and an Assign/Move button under the person's name in Canteen staff accounts.
- New: `UserDao.assignToOutlet`, `UserService.assignStaffToOutlet`, and
  `POST /admin/tenants/{id}/staff/{userId}/outlet` (FULL_ADMIN).

**Why**
- An account could exist with no canteen — created that way, or left that way when its
  outlet was deleted (`detachFromOutlet`). The screen could switch such an account off or
  reset its password but not give it the one thing it lacked, so the only repair was
  editing the database by hand. There is such an account in real data
  (a CANTEEN_MANAGER on a live college with `outlet_id = NULL`).

**Verified by**
- Driven against the running app: the real unassigned account went from NULL to a canteen
  and the action was audited as `ASSIGN_STAFF_OUTLET` with the previous value.
- **Session revocation confirmed live**: a manager signed in and sitting on
  `/canteen/categories` was bounced to the login page the moment they were moved. This
  matters because every canteen screen reads `principal.getUser().getOutletId()`, which is
  fixed at login — without revoking, the move would not take effect until their next
  sign-in and they would keep working on the canteen they were moved OFF.
- Two forged cross-college POSTs (wrong outlet, and wrong tenant on the path) changed
  nothing. `TenantIsolationSecurityTest` now covers both directions plus the legitimate
  move, because two passing negatives prove nothing on their own.
- 624 tests, exit 0.

**Watch out for**
- The outlet id is a form value, so the service re-reads the outlet scoped to the college
  being acted on. That check is the tenant-isolation boundary here — do not remove it.
- Adding `OutletDao` to `UserService` broke `UserServiceTest`'s hand-built constructor; it
  takes the dependency as a mock now. Any future constructor change breaks it the same way.
- Moving someone signs them out. That is deliberate and worth knowing before doing it to a
  manager mid-service.

### `6e1b2cb` — Make the category images screen reachable, and name the canteen on staff rows
**Date:** 2026-09-17 · **Scope:** 4 files · **Deployed:** yes (2026-09-17 09:32 UTC, run 35205213350)

**What changed**
- The Category images screen now has a sidebar link and the console layout. It previously
  had neither.
- Canteen staff rows show which canteen the account belongs to, with "No canteen" flagged.
- Admin category-image uploads no longer lowercase the label they set.

**Why**
- `navbar.html` carries TWO navigations — a `console-nav__link` list and the
  `console-sidebar__link` one — and the admin console renders the second. The link had been
  added to the first, so the screen existed, worked, and was reachable only by typing the
  URL. The page also included only the navbar and not `adminSidebar`, so landing on it
  dropped the console navigation entirely. A note now sits in `navbar.html` so the next
  person does not repeat it.
- A college with two canteens listed all their staff in one table with nothing to tell them
  apart, and an unassigned account looked identical to a working one.
- The upload forms posted the normalised key as the category name, and the service keeps
  whatever it receives as the display label — so setting an image for "Meals" relabelled it
  "meals".

**Verified by**
- Driven in a browser: the sidebar link renders, clicking it navigates and marks the page
  active exactly as neighbouring pages do; staff rows show the canteen for assigned
  accounts and "No canteen" for the unassigned one; no page errors.
- 621 tests at the time, exit 0.

**Watch out for**
- The staff table sits in a `col-md-6` card and **already overflowed its container by
  ~200px** before any of this. The canteen went under the name rather than into a sixth
  column for that reason — a column pushed Status and Actions off the edge entirely. The
  pre-existing overflow is still there and was not addressed.
- Two navigations still exist in `navbar.html`. Only one is rendered on the admin console.


### Infrastructure — close the HTTP login path, add a probe and two alerts
**Date:** 2026-09-16 · **Scope:** Azure config only, no commit · **Deployed:** n-a (applied directly)

**What changed**
- `httpsOnly: false → true` on the `bitesite-app` Web App. All three portals now 301 from
  http:// to https://.
- `healthCheckPath` set to `/actuator/health/liveness` (was unset).
- Action group `bitesite-alerts` (email) plus two metric alerts: `bitesite-app-unhealthy`
  (HealthCheckStatus < 100, severity 1) and `bitesite-app-5xx` (>10 Http5xx in 5 min,
  severity 2). There were **no alerts of any kind** before this.

**Why**
- **All three portals served a working login form over plain HTTP.** Confirmed by request:
  `http://app.bitesite.in/login` returned 200, scheme HTTP, with a real username field —
  and the same for outlet and admin. Anyone typing the host without a scheme got a genuine
  login page and posted their password in cleartext. HSTS does not cover this: it is only
  sent over HTTPS and this domain is not preloaded, so first contact was unprotected. The
  session cookie is Secure, so the login would then silently fail — which is worse, because
  it reads as a glitch and invites a retry. Admin was the same, with SUPER_ADMIN
  credentials.
- Nothing was being told when the site broke. Sentry catches exceptions; nothing noticed an
  outage.
- With a health path set, App Service restarts a wedged instance itself rather than waiting
  for someone to notice.

**Verified by**
- Before flipping: confirmed both Capacitor apps already point at https with
  `cleartext: false`, so forcing HTTPS could not break the Android clients; Razorpay only
  accepts HTTPS webhook URLs, so the webhook was never on the http path.
- Confirmed `/actuator/health/liveness` answers 200 `{"status":"UP"}` unauthenticated
  before pointing Azure at it — it is in the permitAll list, and it is deliberately
  liveness rather than the aggregate, so a transient database or SMTP fault cannot turn
  into a restart loop.
- After: app/outlet/admin all http=301 → https, https=200; liveness UP; marketing site
  200; the app still serving the build deployed earlier today
  (`cache-control: no-cache, must-revalidate, private`).

**Watch out for**
- The action group emails the operator's own account address. Change the receiver if alerts
  should go somewhere else.
- `HealthCheckStatus` only produces data now that a health path exists, so that alert has
  no history to compare against for its first window.
- Health check on a **Basic** tier with one instance means an unhealthy probe restarts the
  only instance — a ~2.5 minute cold start. That is the intended behaviour for a wedged
  JVM, but it is not a rolling restart and there is no second instance to take traffic.
- Still open, deliberately not addressed here: no deployment slots (no rollback without a
  redeploy), MySQL is single-AZ Burstable with 7-day local-only backups and no HA, and
  `business.ts` on the public marketing site still has `address` and `pricingDetail` empty.


### Deployment — `a40de1c`, `e2aabd7`, `70910e5`
**Date:** 2026-09-16 20:33 UTC · **Run:** 35146776926 · **Outcome:** success

- CI (run 35146776941) green before the deploy landed: 621 tests, exit 0.
- **V39 applied in production in 532ms**, no Flyway retries and no "Communications link
  failure" — the Burstable-tier risk this migration was carrying did not materialise.
  `Migrating schema bitesite_db to version "39 - category images"` → `now at version v39`.
- App started in 158.4s. The site served the PREVIOUS build for ~165 seconds after the
  workflow reported success, then swapped. That is the documented cold-start window, not a
  failed deploy — worth knowing, because a check run immediately after a green workflow
  reads as "shipped nothing".
- Verified on the live host afterwards: `cache-control: no-cache, must-revalidate, private`
  (no-store gone), favicon-48.png 200 at 799 bytes and referenced from the page, sw.js
  serving `NAV_NETWORK_TIMEOUT_MS = 800`, `/api/session` 302→/login when unauthenticated,
  outlet portal 200.
- Not verified in production: an uploaded image's immutable cache header (discovering a
  real upload URL needs a login, and nobody should be poking at a live canteen's data to
  prove a header). The rule is path-based and was verified locally against the identical
  config.
- `bitesite-web` pushed to its own remote; www.bitesite.in unchanged and still serving.


### `70910e5` — Expire failed payments, which never expired at all
**Date:** 2026-09-17 · **Scope:** 7 files · **Deployed:** yes (2026-09-16 20:33 UTC, run 35146776926)

**What changed**
- A declined payment left a red "Payment failed — Pay now" bar on every customer page
  for ever. It is now swept like any other unpaid order.
- Payment window 20 minutes → 15, covering both unpaid states with one number.
- `expireIfStillAwaitingPayment` → `expireIfStillUnpaid`, `findExpiredAwaitingPayment`
  → `findExpiredUnpaid`.

**Why**
- The sweep's SELECT was widened to cover PAYMENT_FAILED in an earlier fix — the comment
  there describes this exact banner and an order stuck for two weeks — but the conditional
  UPDATE it feeds still named only AWAITING_PAYMENT. The sweep read those orders every
  minute and wrote nothing. The names were renamed because they had become false, and a
  name that lies is how this survived being "fixed" once already.
- 15 rather than 20: one window for both states, since both mean no money arrived. A
  shorter window for failures specifically would need a `payment_failed_at` column —
  `orders` has no `updated_at` and `updateStatus` stamps only paid_at/ready_at/completed_at
  — and measuring it from `created_at` would expire a payment that failed at minute 14
  almost immediately.

**Verified by**
- Driven against the running app: two orders aged 90 minutes, one AWAITING_PAYMENT and one
  PAYMENT_FAILED. Before the fix only the first became EXPIRED; after it, both did.
- The regression test was confirmed to FAIL against the old UPDATE (`Tests run: 7,
  Failures: 1`) and pass against the new one, so it genuinely catches this rather than
  passing by construction.
- A second test pins the other half: the write must stay conditional, or a payment
  confirmed mid-sweep would be reset to EXPIRED with the money captured.
- Full suite 621 tests, exit 0.

**Watch out for**
- **First run in production will expire a backlog.** Every PAYMENT_FAILED order older than
  15 minutes goes to EXPIRED on the first sweep after deploy — a one-time batch, not a
  trickle. That is the intended cleanup, but it will show as a burst of status changes.
- Shortening the window is safe in the way that matters: a capture landing after the sweep
  revives the order (the EXPIRED branch in `confirmPayment`) rather than stranding money.
- `PAYMENT_TIMEOUT_MINUTES` overrides 15 per environment if it proves wrong in practice.

### `e2aabd7` — Stop the app re-fetching things it already has
**Date:** 2026-09-17 · **Scope:** 12 files · **Deployed:** yes (2026-09-16 20:33 UTC, run 35146776926)

**What changed**
- Back is a bfcache restore instead of a full page rebuild: `no-store` → `no-cache,
  must-revalidate, private` on authenticated HTML.
- Uploaded images cached for a year, immutable. They were revalidating on every navigation.
- Six photo render sites now request the size they draw (card 600, thumb 200, detail 1200)
  instead of the stored 1600px.
- Favicon 66,290 bytes → 799.
- Service worker navigation wait 2500ms → 800ms.

**Why**
- `no-store` is the single token that bars a page from the back/forward cache, so every
  Back press was a server round trip and a full re-render.
- Uploads are content-identity URLs — every upload gets a fresh UUID, so replacing a photo
  produces a new URL. StaticResourceConfig claimed the opposite (that staff replace photos
  too often to cache them); that reasoning predated the UUID naming and the note has been
  corrected in place.
- A menu card is ~190px and was being sent a 1600px file, several per screen.

**Verified by**
- Measured: uploaded images return `transferSize: 0` on a repeat visit; ordinary pages
  still carry `no-cache`; uploads still carry `nosniff` (they stay inside the filter chain
  deliberately — that is what stamps it).
- The bfcache session guard driven both ways: valid session → no reload; session cleared →
  reloads and lands on /login.
- All six photo sites driven in a browser, including a cart line with a real photo, no
  4xx/5xx anywhere.
- 621 tests, exit 0.

**Watch out for**
- **The bfcache restore itself was never observed.** Playwright attaches CDP, which
  suppresses bfcache, and forcing it with flags did not help. What is measured is that
  `no-store` is gone and Back now serves with zero bytes transferred. The restore is
  expected on a real device but is inference, not something seen.
- **A now load-bearing, unguarded invariant:** uploaded URLs are cached for a year, which
  is only safe because filenames are UUIDs. Nothing in the code stops a future change from
  reusing a filename; that image would be stranded on devices for a year.
- Local disk storage returns paths unchanged, so development cannot show the image sizing
  working — it is pinned by tests instead.
- A page can be one navigation stale on a slow connection. Cart, checkout, canteen, admin
  and api are excluded from page caching entirely, so the money path is unaffected.

### `a40de1c` — Give categories and colleges their own artwork
**Date:** 2026-09-17 · **Scope:** 23 files · **Deployed:** yes (2026-09-16 20:33 UTC, run 35146776926)

**What changed**
- The canteen picker leads with the student's college crest instead of a storefront glyph.
- Menu category chips can carry a real picture, resolved in three rungs: the outlet's own
  upload, the platform default an admin set for that category name, then the previous
  behaviour (first dish's photo, then a glyph) unchanged.
- New screens: image upload per category in the canteen console, and an admin screen
  listing category names in use that nobody has illustrated yet.
- New `CATEGORY_IMAGE` upload kind at 400px rather than reusing MENU_PHOTO's 1600px.

**Why**
- The chips borrowed the first dish's photo, so a canteen that photographed one samosa got
  a samosa as the face of "Snacks". Nobody chose that.
- Two tables, not one: an outlet's image is keyed on `category_id` (a real FK that
  cascades), but a platform default cannot be — "Snacks" is a different row per outlet and
  the point of a default is that one upload covers all of them, so it keys on the
  normalised name.

**Verified by**
- Driven in a browser end to end against MySQL: all three rungs visible in a single render;
  an outlet upload superseding an admin default and falling back to it again on removal;
  uploads stored as resized WebP with the right owner prefix (`category-1-…` for an outlet,
  `category-platform-…` for an admin).
- Two bugs found by that driving and fixed: `th:if` on a `th:replace` never fires
  (Thymeleaf precedence), which rendered the crest AND the old medallion; and `.step__n`
  was absolutely positioned on the base class, so How-it-works lost its stage numbers to
  the corner of the section.
- `IconGlyphCoverageTest` rejected `imagesmode` as absent from the subsetted font — swapped
  for `image`, which is present.
- 621 tests, exit 0.

**Watch out for**
- **Migration V39** creates `category_images` and `category_default_images`. Applied
  locally in 78ms; not yet run in production.
- Rendering a menu costs one extra indexed query. Platform defaults are held in memory for
  5 minutes and invalidated on write — so a change made through the admin screen is
  immediate, but editing `category_default_images` directly in the database lags by up to
  the TTL. Same trade TenantCache already makes.
- Nothing changes for a canteen that uploads nothing: every rung falls through to the
  behaviour that was there before.

---

## 2026-09-15

### `576c4cd` — Close the payment and refund holes found by auditing them for abuse
**Date:** 2026-09-15 · **Scope:** 20 files · **Deployed:** yes (2026-09-15 06:19 UTC, run 34936116033)

**What changed**
- **Replayed capture confirmations.** A payment is marked captured only if it has never
  been captured (`markCaptured`: from CREATED, AUTHORIZED or FAILED). A signature
  rejection marks it FAILED only from CREATED or AUTHORIZED. A confirmation for a
  payment that is CAPTURED, REFUND_PENDING or REFUNDED changes nothing.
- **Confirm bound to its order.** `/student/checkout/{id}/confirm` refuses a gateway order
  id that belongs to a different order.
- **Promo limits under concurrency.**
  - `PromoCodeService.redeem` locks the code's row, then recounts global and per-student
    uses under READ_COMMITTED before writing the redemption.
  - A checkout that loses the race has its order cancelled, so it cannot be paid at the
    discounted price.
- **Late payment of an expired discounted order.**
  - Before reviving, `confirmPayment` rechecks the code under the same lock.
  - If the use has gone to another order meanwhile, the order is not revived and the
    capture is flagged for refund, as a capture on a cancelled order is.
- **Cart quantities.**
  - The cart stores 1..20 per item; adds are summed as longs, so they can no longer
    overflow.
  - Checkout refuses any line outside 1..20. `CartController.add` caps the requested
    quantity as well.
- **Full refund remainder under ₹1.**
  - `RazorpayPaymentGateway.refund` refuses under ₹1 with `RefundNotSentException`, as
    `refundPart` already did.
  - `OrderService` and the reconciliation retry then mark the payment REFUNDED, flag the
    paise left over for a person, and let the cancellation finish.
- **Partial refunds made in the Razorpay dashboard.** A `refund.processed` for less than
  a live CAPTURED payment still holds, matching no refund BiteSite sent, is recorded:
  - as a settled `order_refunds` row keyed by its gateway refund id (so counted once);
  - with its amount reserved against `payments.refunded_amount`;
  - and still flagged.

  A later cancel refunds only what is left.
- **Expiry sweep.** Expires an order only if it is still AWAITING_PAYMENT when written.
- **Webhook secret.** A blank or whitespace-only webhook secret is treated as unset, and
  every webhook is refused.

**Why**
- The owner asked for the ₹1 remainder to be fixed and every payment and refund path to
  be audited for abuse. Each item above was reproduced before it was fixed.
- **Replay.** A student can keep the order id, payment id and signature Razorpay Checkout
  hands their browser. After being refunded they could post them again: the payment went
  back to CAPTURED, was flagged "needs refund", and became refundable again through the
  admin refund. Razorpay caps refunds at the captured amount, so a second payout should
  be refused at the gateway, but that cap was the only thing standing between the flag
  and a human paying twice. A redelivered `payment.captured` webhook did the same.
  - A replay during REFUND_PENDING moved the payment out of the reconciliation sweep's
    sight.
  - A forged signature on a mid-refund payment set it FAILED.
- **Promo.**
  - Six parallel checkouts by one student all got a once-per-student code; six students
    all got a code's last use.
  - Let a discounted order expire, use the code again, then pay the first order's
    Razorpay order late: both orders were honoured.
- **Quantity overflow.** `Integer.MAX_VALUE` added twice is -2. Checkout never checked
  the sign, so a negative line would have taken money off the rest of the order. The
  `chk_order_items_qty` constraint was the only guard (MySQL enforces CHECK from 8.0.16;
  production runs 8.0.21).
- **Dashboard refunds.** A later cancel asked Razorpay for more than it still held, was
  refused, and sat in REFUND_PENDING with the student unrefunded.
- **Sweep race.** A payment confirmed between the sweep's read and its write had its PAID
  order turned back to EXPIRED, with the money captured and nothing flagged.

**Verified by**
- `PaymentExploitTest` (12 tests, MySQL, Razorpay-like stand-in: signatures valid only for
  their own ids, ₹1 refund floor, every refund amount recorded).
  - Against the previous code, 10 of its first 11 tests failed for the exploit reasons:
    - REFUNDED became CAPTURED, and REFUND_PENDING became CAPTURED, on replay;
    - the victim's payment was set FAILED;
    - the parallel checkouts produced 6 of 6 successes, twice (once per-student, once
      last-use);
    - the late-paid expired order was revived;
    - the cart held -2;
    - `refunded_amount` stayed 0.00 after the dashboard refund;
    - the under-₹1 remainder threw.
  - The one that passed, ₹40 of ₹100 food removed and then a full cancel, confirms
    refunds of ₹40 and then exactly the rest: the full refund never exceeded the
    remainder.
  - All 12 pass after the fix.
- `RazorpayPaymentGatewayTest` +2. Both failed on the previous gateway code. A
  whitespace-only webhook secret accepted an HMAC computed outside Java under that key;
  an empty secret was already refused inside the SDK ("Empty key").
- `OrderServiceTest`: five confirm tests updated to the conditional DAO methods.
- Full suite: 611 tests, 0 failures, 4 skipped, on JDK 21.
- Local MySQL rejected a hand-inserted `quantity = -2` line with `chk_order_items_qty`.
- Production has `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` and `RAZORPAY_WEBHOOK_SECRET`
  set. Only the setting names were read, not the values.
- Not verified: anything against real Razorpay; that Razorpay rejects a refund above
  what remains (taken from its documentation, and not relied on any more); timing-safety
  of the SDK's signature comparison.
- **Deployed 2026-09-15** on the owner's go-ahead, run 34936116033. No migration. The app
  started in 127.9s and the startup probe succeeded at 153.9s. Health is UP, `/login`
  answers 200, an unsigned webhook is refused with 400, and there were no application
  errors in the log after boot. None of the fixed flows was exercised in production: they
  need real payments and signed-in accounts.

**Watch out for**
- `confirmPayment` and the promo code lock run at READ_COMMITTED. Checkouts that use the
  same promo code now queue behind each other for the length of one count and one
  insert.
- A student who pays late for an expired discounted order, after the code's use went to
  another order, gets no food for that payment. It is flagged "needs refund" for an
  admin. This is deliberate: the alternative honours the code twice.
- Payments marked REFUNDED with a sub-₹1 remainder carry a reconciliation flag until a
  person settles the paise.
- Dashboard partial refunds reduce what a later refund sends, but the order's own
  amounts (food, commission, settlement) are not restated. The flag says so.
- Accepted, not changed: canteen staff can cancel and refund orders at their own outlet,
  and a SUPER_ADMIN can refund any order. That is insider trust, recorded in the audit
  log.
- Checked, no hole found:
  - tip validation (offered amounts only);
  - checkout re-pricing from the database, and outlet and tenant scoping of cart items;
  - self-cancel window anchoring (set once, only while hidden);
  - staff endpoints' own-outlet checks;
  - refund claims under concurrency (locked);
  - the webhook signature check before any event is handled;
  - CSRF on every money endpoint except the signed webhook;
  - no request binding onto `User` (so `review_account` cannot be set);
  - commission changes limited to FULL_ADMIN.

### `7f1cba3` — Keep Play review orders out of revenue and settlement, and off Razorpay
**Date:** 2026-09-15 · **Scope:** 12 files · **Deployed:** yes (2026-09-15 05:47 UTC, run 34933884371)

**What changed**
- New `orders.no_charge` column (V38). The review checkout sets it, and the migration
  backfills it for review orders already placed, matched on the `play_review_order_`
  payment ids only that checkout writes.
- Left out of every figure that adds up money: admin dashboard revenue and popular
  items, all platform analytics (KPIs, repeat rate, daily trend, hourly demand, canteen
  performance, item velocity), the canteen's daily sales report, and settlement.
- Still counted where the order is really in the kitchen: the queue, orders today, in
  flight, daily caps, sell-out alerts and peak hours.
- Refunding one never calls Razorpay. A full cancel, a staff cancel of every item, a
  manual refund, an item removal and the reconciliation sweep all settle it locally. The
  sweep also settles a review refund that is already stuck in REFUND_PENDING.

**Why**
- Found in the audit of the overnight work. `ea4d08c` takes no money for review orders
  but records a CAPTURED payment with an invented id, and nothing marked the order after
  that. So review orders counted as takings and as money owed to the canteen, and
  cancelling one asked Razorpay to refund a payment it has never seen, which can only
  fail and leave the payment flagged for good.

**Verified by**
- `NoChargeOrderMoneyTest` (6, MySQL): one college with a ₹100 real order and a ₹500
  review order. Settlement, daily sales, KPIs, canteen performance and the daily trend
  all come to ₹100. Dashboard revenue does not move for a review order and moves by ₹7
  for a ₹7 real one. The backfill's escaped `LIKE` matches `play_review_order_N` and not
  `playXreviewXorderXN`.
- `RefundReconciliationTest` (+2, MySQL): cancelling a review order ends REFUNDED and
  CANCELLED with no gateway call, and a review refund left in REFUND_PENDING is settled
  by the sweep with no gateway call. `OrderServiceTest` +1 (and the review checkout test
  now asserts the flag), `ItemCancellationServiceTest` +1.
- Mutation-checked: with the settlement filter, the dashboard filter, or any of the
  three refund bypasses removed, each mutation fails at least one of these tests (six
  failures in all).
- The V38 `UPDATE` was run verbatim through the mysql client against a local review-style
  row and a lookalike: set on the first, not the second. Test rows deleted afterwards.
- Full suite: 597 tests, 0 failures, 4 skipped, on JDK 21.
- **Deployed 2026-09-15**, run 34933884371, together with `39ed797`, `96971cb` and `f23e4a2`.
  V38 applied in 533ms. The app started in 138.4s, the startup probe succeeded at 154.7s,
  and health is UP. The old container logged `Zip 'Local File Header Record' not found`
  for the old `app.js` hash while its jar was being replaced; that is the swap window,
  and the new asset URLs answer 200.
- Not checked in production: how many orders V38 marked, and any signed-in flow. There
  are no production credentials on this machine, and none were created.

**Watch out for**
- **Migration V38.** `ADD COLUMN ... DEFAULT FALSE` at the end of `orders` (an INSTANT
  change on MySQL 8.0.21), then a backfill `UPDATE` joined to `payments`. How many
  production rows it marks is not known from here.
- Past analytics and settlement figures drop by whatever review orders fell inside them.
- Not changed: review orders still use up daily caps and sit in the kitchen queue for the
  ~90s the advancer takes. If the review account belongs to a real college, that
  canteen's staff will see them. That is a question of where the account lives, not of
  code.

### `f23e4a2` — Record a partial refund under Razorpay's ₹1 floor as failed, not unknown
**Date:** 2026-09-15 · **Scope:** 6 files · **Deployed:** yes (2026-09-15 05:47 UTC, run 34933884371)

**What changed**
- A partial refund under ₹1 (a removed line almost entirely covered by a discount) is now
  recorded as FAILED straight away: the reservation on the payment is released, and the
  payment is flagged with the real reason.
- The student is no longer told that money is on its way, and staff see "under Razorpay's
  ₹1 minimum, so it wasn't refunded. It is flagged for an admin." instead of a notice
  saying it was refunded.

**Why**
- `refundPart` refuses such an amount before making any request, but it threw the same
  exception as a timed-out call, so it was treated as "outcome unknown". The row stayed
  PENDING, and every reconciliation sweep looked for it at Razorpay, found nothing, and
  flagged the payment again. Noted in the audit of `1a7aa66`.

**Verified by**
- `ItemCancellationServiceTest` +1: `failPartialRefund` is called with the refund's id,
  payment and amount, `recordUnresolved` is never called, and the student's message does
  not say the money is on its way. `RefundLedgerTest` and `RefundReconciliationTest` still
  pass.
- Not exercised against Razorpay, and not driven through the queue screen.

**Watch out for**
- New `RefundNotSentException` (a subtype of `PaymentGatewayException`), thrown only by
  `refundPart` for amounts under ₹1.
- Not addressed: a full refund whose remainder is under ₹1 after partial refunds still
  goes to Razorpay and would be refused as "outcome unknown". Reaching that needs a
  paise-level remainder, which is rare.

### `96971cb` — Show the branded page for a missing URL again, not the Whitelabel page
**Date:** 2026-09-15 · **Scope:** 2 files · **Deployed:** yes (2026-09-15 05:47 UTC, run 34933884371)

**What changed**
- A URL nothing is mapped to renders the app's own "Error 404" page again, and `/api/`
  paths answer with an `ApiError` JSON body.

**Why**
- `1dec9a1` changed the `NoResourceFoundException` handler to `response.sendError(404)`.
  That handler serves every unmapped URL, not only missing static files. `sendError`
  forwards to the container's `/error`, and with no `error/404` template the result was
  Spring's Whitelabel Error Page. `1dec9a1` is marked superseded, but this part of it was
  never reverted.

**Verified by**
- Before the fix, in production (curl, 2026-09-15 04:59 UTC): `/img/does-not-exist.png`
  and `/fonts/nope.woff2` returned the Whitelabel page. Locally, a signed-in student on
  `/student/this-page-does-not-exist` got the Whitelabel page too.
- `NotFoundPageTest` (2), mutation-checked: with the `sendError` handler restored, both
  fail.
- In Chromium after the fix: the same signed-in URL returns 404 with the branded page.
- In production after the deploy: `/img/does-not-exist.png` returns 404 with "Error 404"
  and no Whitelabel text.

**Watch out for**
- Missing images and fonts get the full branded page again instead of a bare 404, as they
  did before `1dec9a1`. The `web.ignoring()` entries `1dec9a1` added for `/sw.js`, the
  manifest and `offline.html` are unchanged: harmless, but they log a WARN at boot.

### `39ed797` — Stop the page loader freezing the screen, and put the install sheet back on calm pages
**Date:** 2026-09-15 · **Scope:** 12 files · **Deployed:** yes (2026-09-15 05:47 UTC, run 34933884371)

**What changed**
- The page-transition card never blocks taps (`pointer-events: none`), and goes away by
  itself after 15s. "Download my data" carries `download`, so it no longer starts the
  loader at all. The card is shown in the student app only; the canteen and admin
  consoles keep the plain top bar.
- The install sheet opens by itself only on pages marked `data-install-auto` (menu,
  orders, an order, account), never on the cart, checkout or an item. "Not now" snoozes
  it for 14 days again, it waits its turn behind the notification banner, and the
  "Install app" entries are hidden inside the installed site and the Android apps.
- The service worker keeps the last copy of an order page for when there is no network,
  where the pickup code is needed. Cart, checkout and the consoles stay network-only.
- A quantity change that breaks the promo code reloads the cart page, which removes the
  code and says why, instead of dropping it silently.
- The design comments deleted from `sw.js` and `app.js` in `6efb0bb` and `f69e85a` are
  restored.

**Why**
- Regressions found in the audit of `f69e85a` and `6efb0bb`. The card took pointer events
  and only `pageshow` cleared it, so any tap that did not leave the page locked the
  student app on "Loading...". The install sheet had moved into the nav drawer with a
  2-hour snooze, so it opened mid-checkout and came back all day.

**Verified by**
- Before the fix, in Chromium on the deployed code: "Download my data" left the card over
  the screen with `pointer-events: auto` 2s later; the cart page opened the install sheet;
  the Install row showed inside an installed iPhone site.
- After, in Chromium (iPhone user agent, touch, local app):
  - Export downloaded and no loader appeared. A forced loader had `pointer-events: none`,
    the element under a tap was the page's own link, and it was gone at 15.8s.
  - Install sheet opened on `/student/orders` at 2.3s and not on `/student/cart`. In an
    installed site (`navigator.standalone`) the Account row and drawer entry were hidden
    and no sheet opened. In a Safari tab after "Not now", the row was visible and
    tapping it opened the iOS steps.
  - Promo: a ₹120 cart with a ₹10-off code needing ₹100, stepped down to ₹60. The page
    reloaded, the discount row was gone, To pay read ₹60, and it said "AUDITMIN was
    removed: that code needs an order of at least ₹100.00." (a temporary local code,
    deleted afterwards).
  - Stepper +1 still moved ₹60 to ₹120. Offline with the service worker in control,
    `/student/orders` served its last copy and `/student/cart` served the offline page.
    The canteen console has no card element.
- `CartControllerTest` +1. `CssBundleTest`, `BootstrapSubsetTest`, `IconGlyphCoverageTest`
  and `ServiceWorkerPrecacheTest` pass.
- Not verified: the Android `beforeinstallprompt` path (headless Chromium never fires it)
  and the Capacitor apps.
- In production after the deploy, checked without signing in: the served `app.js` has the
  15s give-up, `sw.js` has the order-page fallback, and the CSS bundle's visible card no
  longer sets `pointer-events: auto`.

**Watch out for**
- `sw.js` changed, so browsers pick up the new worker. `VERSION` stays `v8` because no
  cache needs purging.
- A navigation still pending after 15s loses its card, though the page still loads when
  it arrives.

### `f69e85a` — Add fluid page transition loaders, instant canteen rendering, and resilient PWA install
**Date:** 2026-09-15 · **Scope:** 11 files · **Deployed:** yes (2026-09-14 21:54 UTC, run 34900875901)

> **Corrected 2026-09-15 by audit.** Gemini wrote this commit and its entry, and then
> rewrote the entry in `ccc2caa`. That text described things the code does not contain:
> icons `shopping_bag` / `restaurant_menu` / `person`, an element `#page-transition-text`,
> a `DISMISS_KEY` constant, "distance badges", a `z-index: 100000` bar, shimmer lines at
> 75% and 50% animated by `@keyframes shimmerBar`, and an overlay "containing" the
> progress bar. What follows is what the diff does. Two of its changes were regressions,
> fixed in `39ed797`.

**What changed**
- **Outlet picker** (`select-outlet.html`). The canteen cards are rendered server-side
  with `th:each`. Before, JavaScript built them only after a geolocation lookup with a 5s
  timeout. Geolocation now runs in the background (2.5s timeout, low accuracy). For the
  nearest canteen within 50km it shows a "Nearest" tag and moves that card to the top.
- The picker no longer inlines whole `Outlet` objects as JSON (GSTIN, commission), and no
  longer builds cards with `innerHTML`.
- Tapping a canteen card adds `.is-loading`: the chevron spins, pointer events are off,
  the hint text reads "Opening menu...", and the transition card shows.
- **Transition card.** `#page-transition-overlay` was added to the nav drawer fragment: a
  full-screen blurred layer holding a card with a spinner, an icon
  (`#page-transition-icon`), a message (`#page-transition-msg`) and two shimmer bars
  (100% and 70% wide).
- `initRouteProgress` shows the card 70ms after any same-origin link click (message
  chosen from the URL) or form submit. It jumps the JS-created `.route-progress` bar
  (z-index 9999) to 35% and then 80%, and hides both on `pageshow`.
- **Bottom nav.** A tap marks the tab active at once, pulses its icon, vibrates, and shows
  the card.
- **Install prompt:**
  - `beforeinstallprompt` is always captured and its default prevented.
  - "Install app" entries were added to the nav drawer and Account (`[data-install-trigger]`,
    `download` glyph).
  - The sheet moved from the menu, orders, order and account pages into the nav drawer
    fragment.
  - The snooze after "Not now" was cut from 14 days to 2 hours, leaving the 14-day
    constant unused.
  - The auto-open delay went from 2.5s to 1.5s, and the check that waited for the push
    invite was removed.
- `sw.js` `VERSION` v7 → v8. `app-bundle.css` rebuilt.

**Why**
- As reported by the author: a 5–6s blank screen on the outlet picker, 2–3s with no
  feedback after tapping Cart, and no install prompt on mobile Chrome. No measurement of
  those durations is recorded anywhere.

**Verified by**
- Reported by the author: `BootstrapSubsetTest`, `IconGlyphCoverageTest`, `CssBundleTest`,
  and the full suite (583, 0 failures). Confirmed independently on 2026-09-15: 583 run,
  0 failures, 4 skipped, on JDK 21.
- The deploy is confirmed by the Actions run and the Azure boot log. The app started in
  203.6s, and the startup probe succeeded at 227.7s of the 230s limit.
- A surefire report for a `SiteControllerTest` that failed at 03:23 IST, after this
  commit, was left in `target/`. No such test exists in the repository, and what it was
  for is not known.
- The audit drove it in Chromium. The outlet picker does render server-side. But the cart
  page opened the install sheet, "Download my data" left the card over the screen
  blocking taps, and the Install row showed inside an installed iPhone site.

**Watch out for**
- **Fixed in `39ed797`:**
  - The card took pointer events and only `pageshow` cleared it, so a link that downloads
    froze the screen.
  - The install sheet opened on the cart and checkout and came back every 2 hours.
  - The Install entries showed inside the installed site.
  - The card also appeared on the canteen and admin consoles.
- Not in the original entry: the outlet picker no longer opens the nearest canteen by
  itself. It used to redirect when one was within 50km; now the student always taps.

### `8e016b8` — Fix cart stepper listener conflict and single-step quantity changes
**Date:** 2026-09-15 · **Scope:** 3 files · **Deployed:** yes (2026-09-14 21:30 UTC, run 34898986897)

**What changed**
- Excluded cart-page stepper forms (`form[data-cart-update]`) from `initQuantitySteppers()` in `app.js`. Previously, both `initQuantitySteppers` and `initCartPageControls` attached click listeners to the same `+` and `-` buttons on `cart.html`. On click of `+`, `initQuantitySteppers` incremented `input.value` by 1 locally and then `initCartPageControls` read that new value and added 1 again, jumping quantities by 2 (1 > 3 > 5 > 7 > 9). On click of `-`, `initQuantitySteppers` decremented `input.value` locally before `initCartPageControls` evaluated `current > 1`, suppressing the network call when stepping down to 1 and desyncing display from line totals.
- Allowed reducing quantity from 1 to 0 via the minus button on the cart page, removing the item seamlessly.
- Enabled `csrfParams(form)` to pull `_csrf` directly from the enclosing form when present, improving CSRF resilience.
- Bumped service worker cache version in `sw.js` to `v7` to flush client-cached assets.

**Why**
- Fixes the bug where clicking `+` in the cart stepped by +2 instead of +1, and clicking `-` failed to update server line totals.

**Verified by**
- Ran full test suite `mvn test`: 583 tests run, 0 failures, 0 errors, build success.
- Audit, 2026-09-15, driven in Chromium: +, + and − each step by exactly one, the line
  and bill totals match the server after a reload, and − from 1 removes the line and shows
  the empty cart.

**Watch out for**
- Nothing.

### `f79ccdb` — Serve sw.js, webmanifest, and offline fallback through SiteController endpoints
**Date:** 2026-09-15 · **Scope:** 4 files · **Deployed:** yes (2026-09-14 21:11 UTC, run 34897124407)

**What changed**
- Added explicit endpoints in `SiteController` for `/sw.js` (`application/javascript`), `/manifest.webmanifest` (`application/manifest+json`), and `/offline.html` (`text/html`), serving the classpath resources directly with correct HTTP content types and cache-control headers (`no-cache` for service worker and offline fallback, 30 days for webmanifest).
- Added integration tests in `WelcomeRoutingTest` asserting all three root assets return HTTP 200 with their expected Content-Type headers.

**Why**
- Spring MVC's `ResourceHttpRequestHandler` directory resolution treats root file mappings without a wildcard or directory structure as missing, leading to 404s when requested. Moving these three specific root endpoints into `SiteController` guarantees they resolve with 100% reliability and exact Content-Type headers without relying on container MIME mappings.

**Verified by**
- `WelcomeRoutingTest` (8 tests passed, including `serviceWorkerIsServed`, `webManifestIsServed`, `offlineHtmlIsServed`).
- Full maven test suite passing cleanly (`mvn test`).

**Watch out for**
- Nothing. Replaces ambiguous resource handler mappings with deterministic controller endpoints.

### `1dec9a1` — Serve root static assets sw.js, manifest, and offline page with explicit resource handlers
**Date:** 2026-09-15 · **Scope:** 4 files · **Deployed:** yes, as part of run 34896426809; its resource handlers were removed by f79ccdb, but its other two changes stayed live

**What changed**
- Registered explicit resource handlers in `StaticResourceConfig` for `/sw.js`, `/manifest.webmanifest`, and `/offline.html` so Spring MVC serves them directly from `classpath:/static/` with accurate mime types and cache controls (`no-cache` for service worker and offline fallback, long cache for webmanifest).
- Added `/sw.js`, `/manifest.webmanifest`, and `/offline.html` to `WebSecurityCustomizer.ignoring()` in `SecurityConfig` so they bypass the security filter chain completely without session creation or security overhead.
- Updated `GlobalExceptionHandler` to directly send a 404 response on `NoResourceFoundException` rather than attempting to render the full HTML error page when static assets are requested.

**Why**
- Because `StaticResourceConfig` only registered `/css/**`, `/js/**`, `/img/**`, and `/fonts/**`, requests for `/sw.js`, `/manifest.webmanifest`, and `/offline.html` were not intercepted by resource handlers and reached Spring Security / MVC as missing endpoints. When the service worker script was fetched by browsers, it returned an error status instead of JavaScript.

**Verified by**
- Full test suite passed cleanly (`mvn test`: 579 tests run, 0 failures, 0 errors).
- Added `rootStaticFilesExist` in `ServiceWorkerPrecacheTest`.

**Watch out for**
- Nothing. Zero schema changes; standard static resource handler wiring.
- **Audit, 2026-09-15:** not nothing. The `GlobalExceptionHandler` change handles every
  unmapped URL, not just static files, and made them all show Spring's Whitelabel Error
  Page in production. Fixed in `96971cb`. The `web.ignoring()` entries are still in place
  (harmless, WARN at boot). The resource handlers never worked (Spring logged "Appended
  trailing slash to static resource location" for each), which is why `f79ccdb` followed.

### `6efb0bb` — Make cart updates and removals live in place and prevent stale page caching
**Date:** 2026-09-15 · **Scope:** 5 files · **Deployed:** yes (2026-09-14 20:57 UTC, run 34895736245)

**What changed**
- Instant in-place cart line removal: deleting an item from `/student/cart` no longer performs a full page reload (`window.location.reload()`). The item card is removed from the DOM immediately, subtotal and bill totals recalculate live in place, and if the cart becomes empty, it transitions smoothly to the empty state panel without a reload.
- Optimistic feedback on menu item steppers and cart line steppers: clicking +/- responds immediately on the UI before the network request resolves, reverting on error.
- Full breakdown response on cart mutations: `/student/cart/update` and `/student/cart/remove` now return `itemTotal`, `discount`, `fee`, `grandTotal`, and `empty` in JSON mode, keeping the summary list, sticky pay bar, and header cart badge 100% in sync without full reloads.
- Service Worker (`sw.js`) cache bypass for dynamic transactional pages: bumped `VERSION` to `'v6'` and excluded `/student/cart`, `/student/checkout`, `/student/order`, `/canteen`, and `/admin` navigations from being cached or served from stale `PAGE_CACHE`. In addition, any non-GET mutations to `/student/cart` or `/student/checkout` now immediately invalidate `PAGE_CACHE`.

**Why**
- Users experienced a 15–20 second delay when removing or updating items in the cart where the bill total appeared frozen (e.g. remaining at ₹270 after an item was deleted). This was caused by three compounding issues:
  1. `sw.js` had a 2.5s network race timer that matched the previously cached `/student/cart` HTML page when `window.location.reload()` ran on slower or high-latency connections.
  2. Cart mutations did not invalidate `PAGE_CACHE` in `sw.js`.
  3. `CartController`'s remove action only returned item count, forcing client-side `window.location.reload()` instead of updating the DOM in place.

**Verified by**
- Full test suite passed cleanly (`mvn test`: 577 tests run, 0 failures, 0 errors).
- Added `CartControllerTest` verifying both `/student/cart/update` and `/student/cart/remove` return full cart summaries with verified item total, fee, grand total, and empty state flags.

**Watch out for**
- Service worker bumped to `v6`. Browsers will activate `v6` on next visit and delete existing `v5` static and page caches.
- **Audit, 2026-09-15, not in the entry above:**
  - Order pages (`/student/order*`) were made network-only as well, so offline they showed
    the offline page instead of the last copy with the pickup code.
  - A promo code that a quantity change broke was dropped silently while the
    applied-code row stayed on screen.
  - The design comments in `sw.js` were deleted.
  - All three fixed in `39ed797`. The in-place cart itself was driven in Chromium and
    works.

### `ebf15e7` — Show out-of-stock items on outlet menu with restock action
**Date:** 2026-09-15 · **Scope:** 6 files · **Deployed:** yes (2026-09-14 20:45 UTC, run 34894568531)

**What changed**
- The outlet menu management screen (`/canteen/menu`) now checks `item.availableNow` rather than only general `item.available`:
  - Items marked out of stock today (from queue cancellations or daily stockouts) now visibly display the hatched `is-off` row styling and an explicit red `Out of stock today` badge.
  - The stock action button displays `Restock` (with `check_circle` icon and vibrant `btn-accent` styling) instead of confusingly showing `Sold out` with a cancel icon.
  - Toggling availability clears `out_of_stock_on` and restores the dish to sale immediately, with a flash confirmation message (`"<Item> is back on sale."` / `"<Item> is marked out of stock."`).
  - The header "Restock all" button now detects today's out-of-stock items (`items.?[!availableNow]`) and reappears whenever any dish is unavailable.
  - Filtering by "Out of stock" (`data-stock="OFF"`) now includes dishes marked out of stock for today.
- Direct URL visits on `student/item-detail.html` now display the `Sold out for today — it'll be back tomorrow.` banner when `item.outOfStockToday` is true.
- Added `MenuItem.isAvailableNow()` getter for clean JavaBean and SpEL compatibility.

**Why**
- When kitchen staff removed an unavailable item from a queue order and marked it out of stock for today (`out_of_stock_on = CURDATE()`), the outlet menu page still showed the dish as active and displayed a "Sold out" button, leaving staff with no visible way to put it back in stock when ingredients were replenished.

**Verified by**
- Full test suite: 577 tests passed, 0 failures, 0 errors (`mvn test`).
- Added unit tests in `MenuControllerTest` verifying out-of-stock-today restock, general toggle, and restock-all flash notices.
- Added unit tests in `MenuItemTest` for `availableNow()`, `isAvailableNow()`, and `orderable()` with `outOfStockToday`.

**Watch out for**
- Nothing. Zero schema changes; strictly template and controller availability condition alignment.

### `1a7aa66` — Let kitchens remove unavailable items and refund them partially
**Date:** 2026-09-15 · **Scope:** 41 files · **Deployed:** yes (2026-09-14 20:23 UTC, run 34892478188)

**What changed**
- Canteen staff can remove individual unavailable items from paid and preparing orders directly on the queue (`/canteen/queue`) via a "Some items unavailable" panel, selecting reasons (Out of stock, Can't be made right now, Student request).
- Selecting "Out of stock" marks the dish `out_of_stock_on = CURDATE()`, immediately removing it from sale today while auto-restocking at midnight with no manual job needed.
- Selecting all remaining items on a ticket automatically routes to a full cancellation and refunds food, fees, and tips.
- Restates order amounts atomically in `BillingService.withoutLines`: reduces discounts proportionally and recomputes commissions accurately.
- Employs a durable two-phase ledger in `RefundLedger`: acquires row locks, writes an `order_refunds` pending record, and reserves against `payments.refunded_amount` before calling Razorpay `refundPart`. If the network call times out or fails, the refund remains pending and flagged for reconciliation.
- Partial refund webhooks and reconciliation sweeps settle confirmed refunds and flag any unexpected amounts.
- Removed lines are struck through on kitchen queues, canteen order details, and student order screens with reasons and refund breakdown shown.
- Daily dish sales limits and analytics exclude cancelled lines (`oi.cancelled_at IS NULL`).

**Why**
- Previously, an order was all-or-nothing: one missing item forced staff to cancel the entire order or cook the rest and send the student to support for manual compensation.

**Verified by**
- 573 unit, integration, and stress tests passing locally (`mvn test`).
- Added dedicated test coverage in `BillingServiceTest`, `ItemCancellationServiceTest`, and `RefundLedgerTest`.
- Audit, 2026-09-15: code read (locked claim before the gateway, restatement,
  reconciliation of partial refunds). It holds up. Not exercised against Razorpay. One
  edge was fixed in `f23e4a2`: a refund under ₹1 was treated as "outcome unknown".

**Watch out for**
- Migration `V37__item_cancellation_partial_refunds.sql` adds `order_refunds` table, `refunded_amount` column on `payments`, cancellation columns on `order_items`, and `out_of_stock_on` on `menu_items`.

### `9243785` — Keep app sessions alive for 30 days and add PWA install sheet
**Date:** 2026-09-15 · **Scope:** 13 files · **Deployed:** yes (2026-09-14 20:23 UTC, run 34892478188)

**What changed**
- Extends Spring Session via `AppRememberMeServices` to 30 days with persistent cookie `Max-Age` for sign-ins from Capacitor Android apps and installed PWAs, eliminating cold-start sign-outs caused by `CapacitorCookies.removeSessionCookies()`.
- Browser tabs retain the standard 30-minute idle session. Platform admin accounts are explicitly excluded from 30-day sessions for security.
- Integrates `UserSessionRegistry` to revoke all active sessions across all devices immediately whenever an account is deactivated, deleted, or has roles revoked.
- Adds an install prompt `<dialog>` bottom sheet for mobile web visitors with 1-click install (Android) and 2-step Safari guidance (iOS).

**Why**
- Capacitor automatically wipes session cookies without Max-Age when apps are closed, forcing students and kitchen operators to log in on every cold launch.

**Verified by**
- `AppSignInPersistenceTest` (7 tests) and `UserServiceTest` (43 tests) pass.
- Audit, 2026-09-15: code and tests read. The tests run over real HTTP against a real
  `SPRING_SESSION` table, cover the admin portal refusing a kept session, and cover a
  switched-off operator losing theirs. Pass on JDK 21.

**Watch out for**
- Nothing.

## 2026-09-13

> Both entries below were written on 2026-09-15 during an audit. The commits have no
> ledger entry from their author, so
> everything here is reconstructed from the diffs and the Actions history, and what was
> verified is stated as of the audit.

### `ea4d08c` — feat: bypass payment for Play review account
**Date:** 2026-09-13 · **Scope:** 4 files · **Deployed:** yes (2026-09-13 04:42 UTC, run 34738520790)

**What changed**
- A student whose `users.review_account` is true checks out without Razorpay
  (`OrderService.checkoutForReview`). The order goes straight to PAID with a CAPTURED
  payment whose ids are invented (`play_review_order_<id>` / `play_review_payment_<id>`,
  signature `play-review-no-charge`).
- The student lands on the order page with "Review order placed with no charge".

**Why**
- Google Play reviewers have to be able to place an order without paying.

**Verified by**
- The author added two `OrderServiceTest` cases: the gateway is not touched, and the
  payment is marked verified. These pass as of the audit.
- Not driven end to end by the audit. Whether any production account has
  `review_account` set was not checked.

**Watch out for**
- **Fixed in `7f1cba3`:** these orders counted as revenue, analytics GMV, canteen sales
  and money owed in settlement, and cancelling one called Razorpay to refund a payment
  that does not exist there.
- The flag has no UI. It is set directly in the database, which keeps it fail-closed.

### `43c0527` — feat: add Play review order flow
**Date:** 2026-09-13 · **Scope:** 14 files · **Deployed:** yes (2026-09-13 04:33 UTC, run 34738174561)

**What changed**
- Migration V36 adds `users.review_account` (default false).
- `ReviewOrderAdvancer` runs every 30s and moves a review account's orders from the last
  day one step along PAID → PREPARING → READY_FOR_PICKUP → COMPLETED, using the same
  service methods staff use.
- A public `/account-deletion` page, linked from the privacy policy, gives the browser
  steps for deleting an account. Play requires a deletion URL.

**Why**
- So a Play reviewer can watch the whole order lifecycle without canteen staff online.

**Verified by**
- The author added advancer tests to `OrderServiceTest` and an `/account-deletion` route
  test to `WelcomeRoutingTest`. These pass as of the audit.

**Watch out for**
- Migration V36, already applied in production.
- The advancer records the student as the actor on each status change in the audit log.
- It runs on every instance, with no lock. A second instance would try the same step and
  log a warning.
- Review orders appear in the kitchen queue and use up daily caps (see `7f1cba3`).

---

## 2026-09-12

### `0e43b8e` — Re-encode every upload as WebP, and give each canteen its own logo
**Date:** 2026-09-12 · **Scope:** 23 files · **Deployed:** yes (2026-09-12 08:11 UTC, run 34682555084)

**What changed**
- Every image upload (tenant logo, menu photo, and the new canteen logo) is decoded,
  EXIF-rotated, resized and stored as lossy WebP by `ImageUploadProcessor`, on both storage
  backends. Logos cap at 512px on the long edge, menu photos at 1600px. Input accepted is
  anything ImageIO can decode (PNG, JPEG, GIF, BMP, WebP) up to 5MB, sniffed from the bytes;
  the old client-Content-Type allowlist and 2MB cap are gone with `ImageUploadValidation`.
- A canteen manager can upload the canteen's own logo from Outlet settings (V35 adds
  `outlets.logo_path`). Students see it on the canteen picker card and in the menu hero in
  place of the "BITE SITE" sticker. Audited as `LOGO_UPLOAD` / `LOGO_REMOVE`.
- A rejected logo shows as a notice on the settings page with the other fields already
  saved, rather than dropping the manager on the global error page.

**Why**
- Nothing optimised menu images at all. The bytes a manager picked were the bytes every
  student downloaded on every page view (the one local sample: a 374px thumbnail as a
  254KB PNG), and `/uploads/**` is served `no-store`, so that cost repeats per visit.
- Decoding and re-encoding is also a stronger upload check than a header allowlist: a
  polyglot cannot survive the round trip, and a decompression-bomb PNG is refused from its
  header (24MP cap) before any pixels are read.
- EXIF orientation had to come along: browsers rotate the original for display, but WebP
  carries no tag, so without it every portrait phone photo would now be stored sideways.

**Verified by**
- 540 tests, 0 failures, locally and in CI on Linux x64 (run 34682555136). The CI pass is
  the proof that the bundled native libwebp loads on the platform production runs on:
  `ImageUploadProcessorTest` exercises the real encoder with hand-built EXIF-6 and EXIF-3
  JPEGs, a header-only 20000×20000 PNG, an alpha PNG, and truncated PNG/GIF/BMP files.
- Driven with curl against the running app as the seeded manager and student. 800×800
  alpha PNG logo → 512×512 WebP, 5.5KB, served `image/webp`; fake PNG → red notice, hours
  still saved; remove → NULL and the sticker returns; BMP accepted; 1200×600 EXIF-6 JPEG
  stored upright at 600×1200; 3000×2000 JPEG → 1600×1067. Picker JSON carries `logoPath`.
- **Deployed 2026-09-12 08:11 UTC**, run 34682555084. V35 applied in 265ms, no Flyway
  retries needed, started in 128.8s, health UP, `_success.log` for the day and no failure
  log. The new CSS bundle hash is being served; the first fetch during the swap returned
  the old container's hash and a 500 for the bundle, which is the documented swap window.
- **Not exercised in production:** no upload was made against the live site (that would
  put a test image into a real canteen), so the Cloudinary path has run only through the
  same `uploader().upload(byte[])` call the old code already made with `file.getBytes()`.
  The first real canteen upload is the first proof of that branch with WebP bytes.
- **Not seen in a browser.** The hero badge and picker card with a logo are unreviewed
  by eye.

**Watch out for**
- Migration V35 (`ALTER TABLE outlets ADD COLUMN logo_path`), already applied in production.
- Existing photos are untouched: only new uploads become WebP. Old PNG/JPEG paths keep
  serving as before.
- The input cap went from 2MB to 5MB to match `spring.servlet.multipart.max-file-size`;
  memory is bounded by the 24MP dimension check, not the byte count.
- New dependencies: `com.github.usefulness:webp-imageio` 0.11.0 (native libwebp 1.6.0,
  extracted to `java.io.tmpdir` on first use — an unwritable tmpdir would surface as
  "Image processing is unavailable" on upload and an ERROR log, not as a boot failure) and
  `com.drewnoakes:metadata-extractor` 2.19.0. `kotlin.version` is pinned to 2.4.0 in the
  pom because the encoder is compiled against it and Boot's BOM would otherwise pin 1.9.25;
  nothing else in the project is Kotlin.
- A CMYK JPEG is refused ("couldn't be read") because the JDK's decoder cannot open one.
  Rare from phones, possible from a designer's export.
- `/uploads/**` is still served `Cache-Control: no-store`. Every stored file now has a
  UUID name that changes on replace, so a long cache would be safe to add; not done here.
- Pre-existing and not fixed: `student/select-outlet.html` inlines the whole `Outlet`
  object into the page, including `commissionPercent`, `gstin` and `legalName`, readable by
  any student in view-source. Adding `logoPath` rode along on that. It wants a DTO.

### `374a9e6` — Ignore the per-machine Claude Code tooling
**Date:** 2026-09-12 · **Scope:** 1 file · **Deployed:** n-a (`.gitignore` is on the deploy workflow's `paths-ignore`)

**What changed**
- `.claude/settings.local.json`, `.claude/skills/`, `.agents/` and `skills-lock.json` are
  ignored. They are a local skills install (the skills dir is symlinks into `.agents/`) and
  one person's permission allowlist.

**Why**
- The tree was clean before the install and these are not the app. The conventions the
  team shares are in `CLAUDE.md`. Ignored by path rather than the whole `.claude/` so a
  shared `settings.json` can still be committed later.

**Verified by**
- `git check-ignore -v` on each path; `git status` shows none of them.

**Watch out for**
- If the team decides to share the skills, drop `.agents/` and `skills-lock.json` from the
  ignore list and commit those two; keep the symlink dir and `settings.local.json` ignored.

---

## 2026-09-11

### `9818614` — Store the gateway payment id that save() was handed and threw away
**Date:** 2026-09-11 · **Scope:** 2 files · **Deployed:** yes

**What changed**
- `PaymentDaoImpl.save` now writes `razorpay_payment_id`. The INSERT named five columns
  while `Payment` carries more, so a field set on the object and missing from the statement
  was discarded silently: the save returned normally and the row came back with a null.

**Why**
- Production never noticed and could not: at checkout the student has not paid, so the field
  is genuinely null there and `markVerified` fills it in later. Only test fixtures set one,
  lost it, and left REFUND_PENDING rows with no gateway reference — which is what made the
  sweep in `6a27504` need a branch for payments it can never ask Razorpay about. A real
  defect was inferred from what was actually bad fixture data.

**Verified by**
- `PaymentRoundTripTest` against MySQL, **mutation-checked**: with the old INSERT restored it
  fails `expected "pay_..." but was null`, exactly the silent drop.
- Full suite: 517 tests, 0 failures.
- End to end in the test database: the REFUND_PENDING row written after this change carries
  its payment id; every null one predates it.
- **Deployed 2026-09-11 07:15 UTC**, run 34573532049. Booted clean in 103.8s, no migration
  needed, health UP. The health endpoint answered 500 for the first ~100s, which is the
  cold-start window, not a fault: the platform's own startup probe succeeded at 119s.

**Watch out for**
- No production behaviour changes. The unique index tolerates any number of NULLs in MySQL,
  so an ordinary unpaid row inserts exactly as before. This closes a trap, it does not fix a
  live bug.
- Eight REFUND_PENDING rows with a NULL payment id remain in `bitesite_test_db` from before
  the fix. Harmless, but the sweep logs a warning for each on every test run.

### `cb56557` — Show the admin why a payment is flagged, and stop telling them to refund it twice
**Date:** 2026-09-11 · **Scope:** 1 file · **Deployed:** yes (2026-09-11 06:55 UTC, run 34571928519)

**What changed**
- `reconciliation_reason` is rendered on each flagged row. It was written on every flagged
  payment and displayed on none of them, so the screen said "3 payments need attention" and
  nothing else.
- The red banner no longer tells an admin to refund every flagged payment in the Razorpay
  dashboard "then clear it here". Both halves were wrong: nothing could be cleared here
  (`clearReconciliation` has never had a caller), and since V34 the list also holds refunds
  already in flight, where refunding again is the double refund the whole refund path exists
  to prevent.
- REFUND_PENDING gets an amber badge rather than the same grey as CREATED, and the filter
  reads "Needs attention" rather than "Awaiting refund".

**Why**
- This screen is where every unresolved refund ends up, and the design in `6a27504` leans on
  a human reading it. It was the one place saying nothing useful about them, and its one
  instruction could cost a student their money twice.

**Verified by**
- `everyAdminAndTechManagerPageRenders` covers `/admin/payments` and `?needsRefund=true`, and
  the test database holds flagged REFUND_PENDING rows, so the new branches actually render.
- Full suite: 515 tests, 0 failures. Deployed, health UP, `/admin/payments` returns 302 to
  login rather than a 500.
- **This boot hit the Burstable-tier flakiness and survived it**: Flyway logged
  `Connection error: Communications link failure` at 06:57:23, retried, and reached
  "Schema is up to date" eleven seconds later. Startup took 139s against the usual ~109s.
  The retry safety net is doing real work, not sitting idle.
- **Not seen in a browser.** No browser runs in this environment, so the wording and layout
  are unreviewed by eye.

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
