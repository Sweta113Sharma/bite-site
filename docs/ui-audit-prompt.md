# BiteSite UI audit — task prompt for a review agent

Copy everything below the line into a fresh agent session. It is written to be run by a
cheaper/faster model: every check is mechanical, has a stated pass condition, and needs no
design judgement.

---

## Your task

Audit the BiteSite web UI for visual and interaction defects. **Report only. Do not change
any files.** Produce the report format given at the end.

## Setup

```bash
cd "<REPO_PATH>"
lsof -ti:8080 | xargs kill -9 2>/dev/null
set -a; . ./.env; set +a
mvn -o clean package -DskipTests -q
nohup java -jar target/bitesite.jar > /tmp/bitesite.log 2>&1 &
sleep 40
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/health   # expect 200
```

**Portal routing is by hostname**, so you cannot just use `localhost` for everything.
Chromium refuses `Host` header overrides — launch it with resolver rules instead:

```js
chromium.launch({
  args: ['--host-resolver-rules=MAP app.localhost 127.0.0.1, MAP outlet.localhost 127.0.0.1, MAP admin.localhost 127.0.0.1'],
})
```

Accounts (all password `Demo@12345`):

| Portal | Host | Login |
|---|---|---|
| Customer | `app.localhost:8080` | `student@demo.local` |
| Outlet | `outlet.localhost:8080` | `canteen@demo.local` (manager) |
| Admin | `admin.localhost:8080` | `admin@bitesite.local` |

### Three traps that will silently corrupt your results

1. **Login is rate limited** to 10 POSTs per IP per 5 minutes, stored in MySQL so it
   survives restarts. Log in **once per portal**, save `context.storageState()`, and reuse
   it for every viewport. If you exceed it you get redirected to `/login`.
2. **A redirect to `/login` still returns HTTP 200.** Never treat status alone as proof a
   page rendered. Assert the landed URL contains the path you requested.
   To clear the limiter between runs: `mysql -u root bitesite_db -e "DELETE FROM rate_limit_window;"`
3. **Animations make screenshots non-deterministic.** Before measuring, inject:
   `*, *::before, *::after { animation: none !important; transition: none !important; }`

Test every route at **1280px and 390px**. Wait for `networkidle` and `document.fonts.ready`
before measuring.

## Routes

Customer (`app.localhost`): `/login`, `/register/student`, `/verify`,
`/resend-verification`, `/student/menu`, `/student/menu/select`, `/student/cart`,
`/student/orders`, `/student/account`, `/student/privacy`, `/student/grievances`,
`/privacy-policy`, `/terms`, `/refund-policy`, `/shipping-policy`, `/grievance-policy`

Outlet (`outlet.localhost`): `/canteen/queue`, `/canteen/menu`, `/canteen/menu/new`,
`/canteen/orders`, `/canteen/reports`, `/canteen/settings`, `/canteen/staff`,
`/canteen/categories`

Admin (`admin.localhost`): `/admin/tenants`, `/admin/tenants/new`, `/admin/outlets`,
`/admin/orders`, `/admin/payments`, `/admin/users`, `/admin/support`, `/admin/grievances`,
`/admin/audit-log`, `/admin/dpdp`, `/admin/onboarding`, `/admin/onboarding/new`,
`/techmgr`, `/techmgr/health`

To reach `/student/cart` with contents, and `/student/checkout/{id}`, see **Stateful
screens** below.

---

## The design system (what "correct" means)

Five colours, no others:

| Token | Value | Meaning |
|---|---|---|
| `--color-bg` / `--color-surface` | `#f2ead9` | bone canvas and cards |
| `--color-ink` | `#14100d` | text, borders |
| `--color-primary` | `#e8281e` | red — **destructive and primary action only** |
| `--color-accent-tint` | `#ffc918` | yellow — in-progress, attention |
| `--color-field` | `#fffdf6` | input fill |

Rules: corners are **0–2px** (no rounded pills, no circles); shadows are **hard offset**
(`Npx Npx 0 #14100d`), never blurred; borders are **2–3px solid `#14100d`**; display type
is Archivo Black uppercase, body copy is Archivo sentence case.

---

## Checks

### C1 — Every route renders
Status 200 **and** landed URL matches the requested path. No `pageerror`. No `console`
errors. Report any route failing either.

### C2 — No horizontal overflow
`document.documentElement.scrollWidth <= window.innerWidth + 1` at both widths. If it
fails, report the offending elements:
```js
[...document.querySelectorAll('body *')].filter(el => el.getBoundingClientRect().right > window.innerWidth + 2)
```

### C3 — Icon glyphs resolve
Icons are Material Symbols ligatures: the element's text **is** the icon name. When the
ligature fails, the browser renders the *word* instead of the glyph.

For every `.material-symbols-outlined`, a resolved glyph is roughly square:
`width <= fontSize * 1.6`. Anything wider is broken. Report the icon name, route and
containing element.

The known causes are `text-transform`, `letter-spacing` and `font-variant-ligatures` on an
ancestor. **Also check pseudo-element icons** (rules using `content: 'add'` and similar
with the Material font) — these do not carry the `.material-symbols-outlined` class, so
class-based protections miss them.

### C4 — Off-system colour
For every visible element, read computed `background-color`, `color` and `border-color`.
Flag any value that is not in the palette table above, not a neutral
(`transparent`/white/black), and not a documented tint (`#ffd9d4`, `#e6dcc4`, `#b81f16`,
`#5c5247`). Report the value, the element and the route.

Watch specifically for: Bootstrap blue (`#0d6efd`), Bootstrap green (`#198754`), Bootstrap
grey (`#6c757d`), and any `linear-gradient` or `radial-gradient`.

### C5 — Off-system geometry
Flag any element with computed `border-radius` greater than 2px, or a `box-shadow`
containing a non-zero blur radius. Report selector, value, route.

### C6 — Contrast and invisible text
For every text node, compare its computed `color` against its nearest painted background.
Flag ratios below **4.5:1** for body text and **3:1** for text 24px or larger.

This catches the highest-severity class of bug: text that is present in the DOM but
invisible because it inherited a colour matching its surface. Check dark surfaces
especially — the sticky pay bar on `/student/cart`, the console nav strip on staff pages,
and any red or black filled button.

### C7 — Input padding and affordances
For every `input`, `select` and `textarea`:
- If it has a visible left border, its computed `padding-left` must be **≥ 8px**. Zero
  padding against a border means text sits on the border.
- If it has a button or icon overlaid inside it (password reveal, search icon, select
  chevron), that control must not overlap the text area. Compute
  `icon.getBoundingClientRect()` against the input's content box and report any
  intersection.
- The overlaid control should not draw its own `background-color` or `border` distinct
  from the field — that makes it read as a separate widget.

### C8 — Spacing between adjacent controls
For every pair of vertically adjacent interactive elements (button, link, input), compute
the gap between the first's bottom edge and the second's top edge. **Flag any gap < 8px.**
Report both elements and the route.

Include dynamically injected elements — see **Stateful screens**.

### C9 — Tap targets
Every `a`, `button`, `input[type=checkbox]`, `input[type=radio]` and `select` must be at
least **44×44px** at 390px width, or have that much spacing around it. Report failures.

### C10 — Overlap
Detect elements whose bounding boxes intersect when they should not: a heading overlapping
a button, content sliding under a fixed bar. Method: for each container, check whether any
two non-nested children's rects intersect by more than 2px.

Specifically confirm that on `/student/cart`, when scrolled to the bottom, the last
paragraph's bottom edge is above the sticky pay bar's top edge.

### C11 — Typography
Body copy longer than ~40 characters must not be rendered in uppercase display type. Flag
any element whose `text-content.length > 40` **and** computed `text-transform` is
`uppercase`, or whose `font-family` includes `Archivo Black`. Report route and text.

### C12 — Long and empty content
Repeat C2, C8 and C10 after:
- Setting a college name, outlet name and dish name to 60+ characters
- Emptying a list (no menu items, no orders, no categories, no staff)

Report anything that overflows, wraps badly, or leaves an empty container with a border
and no content.

---

## Stateful screens

Some defects only appear in states a plain page visit will not reach.

**Cart and checkout.** Add to cart by POSTing with the page's CSRF meta pair:
```js
const t = document.querySelector('meta[name="_csrf"]').content;
const n = document.querySelector('meta[name="_csrf_parameter"]').content;
const body = new URLSearchParams({ menuItemId, quantity: '3', outletId });
body.set(n, t);
await fetch('/student/cart/add', { method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString() });
```
Get ids with:
`mysql -u root bitesite_db -N -B -e "SELECT id, outlet_id FROM menu_items WHERE tenant_id=1 AND is_available=1 LIMIT 1"`

Then POST `/student/checkout` and open `/student/checkout/{id}` (the new order id is
`SELECT MAX(id) FROM orders`).

**The payment failure notice.** On the checkout page, run in the console:
```js
showNotice('Payment cancelled. Your order is still held — tap Pay to try again.', 'warning');
```
Then run C6, C8 and C11 against it. This element is injected after the pay button and is a
known source of spacing and typography defects.

**Form validation.** Submit `/register/student` and `/canteen/menu/new` empty. Run C4, C6
and C7 against the resulting error states (`.is-invalid`, `.invalid-feedback`).

**Disabled and loading states.** Find any `button[disabled]` and confirm it does not still
apply a hover transform.

**Order states.** The queue at `/canteen/queue` renders differently per status. Check all
of `PAID`, `PREPARING`, `READY_FOR_PICKUP`. Statuses can be set directly:
`mysql -u root bitesite_db -e "UPDATE orders SET status='PREPARING' WHERE id=<id>"`
(Restore what you change.)

---

## Report format

One row per finding, most severe first. No prose summary.

```
SEVERITY | CHECK | ROUTE | WIDTH | ELEMENT | OBSERVED | EXPECTED
```

- **HIGH** — invisible text, unreachable control, overlap that hides content, broken icon
  in a primary action, contrast below 4.5:1
- **MEDIUM** — off-system colour or geometry, gap under 8px, tap target under 44px
- **LOW** — cosmetic inconsistency with no functional impact

For every finding give the exact selector and the measured numbers. Do not report a
finding you have not measured. If a check produced no findings, write
`CHECK <id>: clean` so it is clear it ran.

## Do not

- Change any file.
- Report subjective opinions ("this looks dated"). Every finding must cite a measurement
  or a rule from the design system above.
- Report the same root cause once per element. Group them: one finding, with the count and
  a list of affected routes.
