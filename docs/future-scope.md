# Future scope

Known work that is not done yet, with the evidence for each. Newest findings come from the
2026-09-18 stress tests (`docs/stress-test-report.md`). Tick items off here when they ship,
and give each its ledger entry as usual.

## Bugs

- [ ] **Two staff can move the same order at once** (`OrderService.advanceStatus`: check then
      update, no status predicate). A second READY re-issues the pickup code, so the code in
      the first "ready" notification is refused at the counter. 11 and 6 orders in two runs.
      *In progress 2026-09-18.*
- [ ] **READY is saved before the pickup code** (two separate writes, no transaction). A 5s
      gap was seen under load; if the second write fails the order can never be handed over.
      Same fix as above.
- [ ] **Saved-cart deadlocks** (`SavedCartDaoImpl.save`: DELETE-then-INSERT gap locks under
      REPEATABLE READ). 10–19 per soak run; the save is dropped, and a failed clear can restore
      a paid-for cart next session. Fix: READ COMMITTED on that transaction plus one retry.
- [ ] **One 30-second connection-pool stall** (soak run 2, local, 13:08 IST). Cause unknown;
      expired-session cleanup was tested and ruled out. The soak test now snapshots MySQL when
      5+ requests wait; read that if it recurs.
- [ ] **Second staff tap shows an error page** instead of a queue message (status endpoint
      does not catch `InvalidOrderStateException`, unlike collect and item-cancel).
- [ ] **Registration and password reset limit every attempt per IP**, the same shared-Wi-Fi
      exposure the login limit had before `c3aa66b`. Lower traffic, same shape.

## Hosting

- [ ] **Move the database next to the app, or both off Azure.** The app runs in Central India
      (Pune), MySQL (Burstable B1ms, 8.0.21) in South India (Chennai); every query crosses
      regions (~150–200ms server time per page in production vs 5–25ms locally). Azure for
      Students cannot create either service in the other region.
      - Watch the **Azure for Students credit**: when it runs out the subscription is disabled
        and the site goes down. The move date should come from that runway.
      - Preferred target: **AWS Lightsail Mumbai, 2 vCPU / 4GB**, app and MySQL on one box,
        Caddy for HTTPS: ~$24/month, ~₹2,700 with backups and GST (2026-09-18 prices).
        2GB plan (~₹1,400) works but is tight.
      - Oracle Cloud Always Free (ap-hyderabad-1) is free but the Pay-As-You-Go upgrade
        refused the card, and free ARM capacity needs `scripts/oci-hunt-arm-vm.sh`.
      - DigitalOcean Student Pack credit no longer exists (ended 2026-08-01).
      - Supabase/Neon mean a MySQL→Postgres port (40 migrations, MySQL-specific SQL). Not worth it.
      - Steps: dump Azure DB → import → switch `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` → DNS for
        app/outlet/admin → move the GitHub deploy → keep Azure a few days as rollback → delete.
        Run the full suite and the soak test against the new DB first.
- [ ] **Confirm production sees real client IPs** (`rate_limit_window` keys). The query could
      not run: the Azure MySQL firewall refuses this machine. Less important since `c3aa66b`.

## Speed on slow networks

- [ ] Lazy-load menu card photos below the first screen (`student/menu.html` 129/161/214).
      A 30-photo menu adds an estimated 0.9–1.8MB to a first visit.
- [ ] Load `image-crop.js` only on staff/admin pages (9.6KB on every student page today).
- [ ] Shrink `img/logo-mark.png` (65KB) and the food illustration PNGs (24–43KB, drawn at 58px).

## Not yet verified

- [ ] Safari (the cropper's JPEG/PNG fallback), real Android devices, the Capacitor WebView.
- [ ] Load against production-like infrastructure (Burstable DB, remote region).
