/**
 * BiteSite — client-side interactions
 * - Bottom nav active state
 * - Menu search filter
 * - Quantity stepper (+/- buttons)
 * - Toast notifications
 * - Cart badge counter
 */

document.addEventListener('DOMContentLoaded', () => {
    initBottomNav();
    initSearchFilter();
    initCategoryChips();
    initQuantitySteppers();
    initBodyClass();
    initAddToCartForms();
    initCartControls();
    initCartPageControls();
    initNavbarScroll();
    initNavDrawer();
    registerServiceWorker();
    initPushToggle();
    initPushInvite();
    initOrderStatusWatch();
    initOfflineState();
});

/* ============================================================
   SERVICE WORKER — enables install-to-home-screen and an
   offline fallback page. See sw.js for the caching strategy;
   it never touches POST requests (checkout, cart, order
   actions always go straight to the network).
   ============================================================ */

function registerServiceWorker() {
    if (!('serviceWorker' in navigator)) return;
    window.addEventListener('load', () => {
        navigator.serviceWorker.register('/sw.js').catch(() => {
            // Registration failing (e.g. an unsupported browser edge case) shouldn't be
            // user-visible — the app works identically without a service worker, just
            // without the offline fallback / install prompt.
        });
    });
}

/* ============================================================
   PUSH NOTIFICATIONS — "order ready" / "order cancelled" alerts.
   One opt-in toggle on the account page; nothing prompts for
   permission unannounced elsewhere in the app.
   ============================================================ */

function csrfParams() {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const paramName = document.querySelector('meta[name="_csrf_parameter"]')?.content;
    const params = new URLSearchParams();
    if (token && paramName) params.set(paramName, token);
    return params;
}

function urlBase64ToUint8Array(base64String) {
    const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
    const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
    const raw = atob(base64);
    return Uint8Array.from([...raw].map((c) => c.charCodeAt(0)));
}

function initPushToggle() {
    const toggle = document.getElementById('push-toggle');
    if (!toggle || !('serviceWorker' in navigator) || !('PushManager' in window)) return;

    navigator.serviceWorker.ready.then((registration) =>
        registration.pushManager.getSubscription().then((sub) => {
            toggle.checked = !!sub;
        })
    );

    toggle.addEventListener('change', () => {
        if (toggle.checked) {
            enablePush(toggle);
        } else {
            disablePush(toggle);
        }
    });
}

function enablePush(toggle) {
    fetch('/api/push/public-key')
        .then((r) => r.json())
        .then((data) => {
            if (!data.configured) {
                toggle.checked = false;
                showToast('Notifications are not set up on this server yet');
                return;
            }
            return navigator.serviceWorker.ready
                .then((registration) => registration.pushManager.subscribe({
                    userVisibleOnly: true,
                    applicationServerKey: urlBase64ToUint8Array(data.publicKey),
                }))
                .then((subscription) => {
                    const key = subscription.getKey('p256dh');
                    const auth = subscription.getKey('auth');
                    const body = csrfParams();
                    body.set('endpoint', subscription.endpoint);
                    body.set('p256dh', btoa(String.fromCharCode(...new Uint8Array(key))));
                    body.set('auth', btoa(String.fromCharCode(...new Uint8Array(auth))));
                    return fetch('/api/push/subscribe', { method: 'POST', body });
                })
                .then(() => { showToast('Notifications enabled'); haptic('success'); })
                .catch(() => {
                    toggle.checked = false;
                    showToast('Could not enable notifications');
                });
        });
}

function disablePush(toggle) {
    navigator.serviceWorker.ready
        .then((registration) => registration.pushManager.getSubscription())
        .then((subscription) => {
            if (!subscription) return;
            const endpoint = subscription.endpoint;
            return subscription.unsubscribe().then(() => {
                const body = csrfParams();
                body.set('endpoint', endpoint);
                return fetch('/api/push/unsubscribe', { method: 'POST', body });
            });
        })
        .then(() => showToast('Notifications turned off'))
        .catch(() => {
            toggle.checked = true;
        });
}

/* ============================================================
   NAV DRAWER — the full destination list.

   The bottom tab bar carries five places and the console strip scrolls; this is the one
   surface that shows everything at once. Hand-rolled rather than pulled from Bootstrap's
   offcanvas, because no Bootstrap JS bundle is loaded on this app.

   Accessibility is the reason for most of the code here: a drawer that is visually hidden
   but still focusable is worse than no drawer at all, because keyboard and screen-reader
   users tab into an invisible menu. `inert` removes the whole subtree from focus and the
   accessibility tree; focus moves in on open and returns to the button on close.
   ============================================================ */

function initNavDrawer() {
    const btn = document.getElementById('nav-drawer-btn');
    const drawer = document.getElementById('nav-drawer');
    const backdrop = document.getElementById('nav-drawer-backdrop');
    const closeBtn = document.getElementById('nav-drawer-close');
    if (!btn || !drawer || !backdrop) return;

    let lastFocused = null;

    const open = () => {
        lastFocused = document.activeElement;
        backdrop.hidden = false;
        // Next frame, so the transition has a starting state to animate from.
        requestAnimationFrame(() => {
            drawer.classList.add('is-open');
            backdrop.classList.add('is-open');
        });
        drawer.removeAttribute('inert');
        drawer.setAttribute('aria-hidden', 'false');
        btn.setAttribute('aria-expanded', 'true');
        // Locking the body stops the page behind from scrolling under the drawer.
        document.body.classList.add('has-drawer-open');
        (closeBtn || drawer.querySelector('a, button')).focus();
    };

    const close = () => {
        drawer.classList.remove('is-open');
        backdrop.classList.remove('is-open');
        drawer.setAttribute('inert', '');
        drawer.setAttribute('aria-hidden', 'true');
        btn.setAttribute('aria-expanded', 'false');
        document.body.classList.remove('has-drawer-open');
        // Hide the backdrop only once it has faded, or it vanishes instantly.
        setTimeout(() => { if (!drawer.classList.contains('is-open')) backdrop.hidden = true; }, 200);
        if (lastFocused && document.contains(lastFocused)) lastFocused.focus();
    };

    btn.addEventListener('click', () => {
        drawer.classList.contains('is-open') ? close() : open();
    });
    backdrop.addEventListener('click', close);
    if (closeBtn) closeBtn.addEventListener('click', close);

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && drawer.classList.contains('is-open')) close();
    });

    // Keep Tab inside the panel while it is open — otherwise focus walks onto the page
    // behind, which is still visible through the backdrop and confusing to land on.
    drawer.addEventListener('keydown', (e) => {
        if (e.key !== 'Tab') return;
        const items = [...drawer.querySelectorAll('a[href], button:not([disabled])')]
            .filter((el) => el.offsetParent !== null);
        if (!items.length) return;
        const first = items[0], last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    });

    // Mark where you are, using the same path logic the console strip uses server-side.
    const path = window.location.pathname;
    drawer.querySelectorAll('.nav-drawer__link').forEach((link) => {
        const href = link.getAttribute('href');
        if (href && href !== '/' && path.startsWith(href)) link.setAttribute('aria-current', 'page');
    });
}

/* ============================================================
   BOTTOM NAV — highlight the active tab
   ============================================================ */

function initBottomNav() {
    const nav = document.getElementById('bottom-nav');
    if (!nav) return;

    const path = window.location.pathname;
    const items = nav.querySelectorAll('.bottom-nav-item');

    items.forEach(item => {
        const page = item.dataset.page;
        const isActive =
            (page === 'menu' && path.includes('/student/menu')) ||
            (page === 'cart' && (path.includes('/student/cart') || path.includes('/student/checkout'))) ||
            (page === 'orders' && path.includes('/student/orders')) ||
            (page === 'support' && path.includes('/student/grievances')) ||
            (page === 'account' && path.includes('/student/account'));

        if (isActive) {
            item.classList.add('active');
            // The filled icon is CSS's job now: .bottom-nav-item.active sets
            // font-variation-settings 'FILL' 1 on the Material Symbol. This used to
            // rewrite Phosphor class names, which a variable font cannot express and
            // which silently stopped matching anything when the icons changed.

        }
    });
}

/* ============================================================
   BODY CLASS — add has-bottom-nav for padding
   ============================================================ */

function initBodyClass() {
    if (document.getElementById('bottom-nav')) {
        document.body.classList.add('has-bottom-nav');
    }
}

/* ============================================================
   SEARCH FILTER — live filter menu items by name
   ============================================================ */

function initSearchFilter() {
    const searchInput = document.getElementById('menu-search');
    if (!searchInput) return;
    const noResults = document.getElementById('menu-no-results');

    searchInput.addEventListener('input', () => {
        const query = searchInput.value.toLowerCase().trim();
        const cards = document.querySelectorAll('.menu-card');
        const categories = document.querySelectorAll('.menu-category-section');
        let visibleCount = 0;

        cards.forEach(card => {
            const name = card.dataset.name || card.querySelector('.menu-card-name')?.textContent || '';
            const match = !query || name.toLowerCase().includes(query);
            card.style.display = match ? '' : 'none';
            if (match) visibleCount++;
        });

        // Hide empty category headers
        categories.forEach(section => {
            const visibleCards = section.querySelectorAll('.menu-card:not([style*="display: none"])');
            section.style.display = visibleCards.length > 0 ? '' : 'none';
        });

        if (noResults) {
            noResults.style.display = query && visibleCount === 0 ? 'block' : 'none';
        }
    });
}

/* ============================================================
   QUANTITY STEPPER — +/- buttons
   ============================================================ */

function initQuantitySteppers() {
    document.querySelectorAll('.qty-stepper').forEach(stepper => {
        // Menu-card steppers are cart-backed and handled by initCartControls();
        // binding this local-only handler to them as well would move the number
        // without ever telling the server.
        if (stepper.closest('.cart-control')) return;
        const input = stepper.querySelector('input[type="hidden"], input[name="quantity"]');
        const display = stepper.querySelector('.qty-value');
        const minusBtn = stepper.querySelector('.qty-minus');
        const plusBtn = stepper.querySelector('.qty-plus');

        if (!input || !display || !minusBtn || !plusBtn) return;

        minusBtn.addEventListener('click', (e) => {
            e.preventDefault();
            let val = parseInt(input.value) || 1;
            if (val > 1) {
                input.value = val - 1;
                display.textContent = val - 1;
            }
        });

        plusBtn.addEventListener('click', (e) => {
            e.preventDefault();
            let val = parseInt(input.value) || 1;
            if (val < 20) {
                input.value = val + 1;
                display.textContent = val + 1;
            }
        });
    });
}

/* ============================================================
   ADD TO CART — submitted via fetch() so the menu page never
   reloads; updates the bottom-nav badge and sticky cart bar
   in place and shows a toast. Falls back to a normal form post
   (full page reload, still correct) if fetch fails or JS never ran.
   ============================================================ */

function initAddToCartForms() {
    document.querySelectorAll('.add-to-cart-form').forEach(form => {
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            // Thymeleaf's Spring Security dialect auto-injects a hidden _csrf input into every
            // th:action form, so FormData already carries a valid token — no extra header needed.
            const body = new URLSearchParams(new FormData(form));

            fetch(form.action, {
                method: 'POST',
                headers: { Accept: 'application/json' },
                body
            })
                .then(response => {
                    if (!response.ok) throw new Error('add-to-cart failed');
                    return response.json();
                })
                .then(data => {
                    updateCartCount(data.count);
                    // The server refuses adds the page can't always know about yet — an
                    // item that just sold out for the day, one staff switched off, a
                    // canteen that paused mid-browse. The stepper is deliberately left
                    // alone in that case: showing "1" for something that is not in the
                    // cart is worse feedback than none.
                    if (data.blocked) {
                        showToast(data.message || 'That item can\'t be added right now.', 'warning');
                        haptic('warning');
                        return;
                    }
                    const control = form.closest('.cart-control');
                    if (control) {
                        setControlQuantity(control, 1);
                    }
                    showToast('Added to cart');
                    haptic('success');
                })
                .catch(() => {
                    // Fetch failed (offline, JS edge case) — fall back to a real submit
                    // so the item still gets added, just with a page reload.
                    form.submit();
                });
        });
    });
}

/* ============================================================
   CART CONTROLS — the Add / stepper swap on menu cards.

   A card shows Add until its item is in the cart, then a stepper bound to
   the real cart quantity. Dropping to zero swaps back to Add. Every change
   is posted to /student/cart/update; the number on screen is only moved
   once the server has confirmed, so a failed request leaves the display
   telling the truth rather than drifting out of sync with the cart.
   ============================================================ */

function setControlQuantity(control, qty) {
    const value = control.querySelector('.cart-qty-value');
    if (value) {
        value.textContent = qty;
        value.classList.remove('is-bumped');
        // Reading offsetWidth forces reflow so the animation restarts on
        // repeated taps instead of only firing the first time.
        void value.offsetWidth;
        value.classList.add('is-bumped');
    }
    control.classList.toggle('is-active', qty > 0);
}

function initCartControls() {
    const updateUrl = document.body.dataset.cartUpdateUrl || '/student/cart/update';

    document.querySelectorAll('.cart-control').forEach(control => {
        const stepper = control.querySelector('.cart-control__stepper');
        const value = control.querySelector('.cart-qty-value');
        if (!stepper || !value) return;

        // The add form already carries a CSRF token; reuse it rather than
        // hunting for a meta tag that may not be there.
        const tokenInput = control.querySelector('input[name="_csrf"]');
        const itemId = control.dataset.itemId;

        const change = (delta) => {
            const current = parseInt(value.textContent, 10) || 0;
            const next = Math.max(0, Math.min(20, current + delta));
            if (next === current) return;

            const body = new URLSearchParams();
            body.set('menuItemId', itemId);
            body.set('quantity', next);
            if (tokenInput) body.set('_csrf', tokenInput.value);

            fetch(updateUrl, {
                method: 'POST',
                headers: { Accept: 'application/json' },
                body
            })
                .then(response => {
                    if (!response.ok) throw new Error('cart update failed');
                    return response.json();
                })
                .then(data => {
                    setControlQuantity(control, data.quantity);
                    updateCartCount(data.count);
                })
                .catch(() => { showToast("Couldn't update your cart"); haptic('error'); });
        };

        stepper.querySelector('.cart-qty-minus')
            ?.addEventListener('click', (e) => { e.preventDefault(); change(-1); });
        stepper.querySelector('.cart-qty-plus')
            ?.addEventListener('click', (e) => { e.preventDefault(); change(1); });
    });
}

/* ============================================================
   CART PAGE CONTROLS — quantity steppers and remove buttons on the
   cart page (student/cart.html) talk to the server via fetch() so
   the line subtotal, grand total and badge update in place instead
   of a full page reload. Each control is wrapped in a form that
   still posts normally if JS never runs, so the non-JS path stays
   correct. The /update endpoint returns {count, quantity, lineTotal,
   total} when asked for JSON.
   ============================================================ */

function initCartPageControls() {
    const updateUrl = document.body.dataset.cartUpdateUrl || '/student/cart/update';
    const removeUrl = document.body.dataset.cartRemoveUrl || '/student/cart/remove';

    const formatMoney = (n) => '₹' + Number(n).toLocaleString('en-IN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
    });

    const setBusy = (form, busy) => {
        form.querySelectorAll('button').forEach(b => {
            b.disabled = busy;
        });
        form.classList.toggle('is-busy', busy);
    };

    const refreshTotals = (data) => {
        if (typeof data.lineTotal !== 'undefined') {
            const card = document.querySelector('[data-cart-line]');
            const sub = card && card.querySelector('.cart-item-subtotal');
            if (sub) sub.textContent = formatMoney(data.lineTotal);
        }
        if (typeof data.total !== 'undefined') {
            document.querySelectorAll('.cart-summary dd, .sticky-pay-total span')
                .forEach(el => { el.textContent = formatMoney(data.total); });
        }
        if (typeof data.count !== 'undefined') {
            updateCartCount(data.count);
        }
    };

    // Quantity steppers: +/- on each line.
    document.querySelectorAll('form[data-cart-update]').forEach(form => {
        const input = form.querySelector('input[name="quantity"]');
        const display = form.querySelector('.qty-value');
        const minusBtn = form.querySelector('.qty-minus');
        const plusBtn = form.querySelector('.qty-plus');
        if (!input || !display || !minusBtn || !plusBtn) return;

        const itemId = form.querySelector('input[name="menuItemId"]')?.value;

        const send = (next) => {
            if (next < 1 || next > 20) return;
            setBusy(form, true);
            const body = csrfParams();
            body.set('menuItemId', itemId);
            body.set('quantity', next);

            fetch(updateUrl, {
                method: 'POST',
                headers: { Accept: 'application/json' },
                body
            })
                .then(response => {
                    if (!response.ok) throw new Error('cart update failed');
                    return response.json();
                })
                .then(data => {
                    input.value = data.quantity;
                    display.textContent = data.quantity;
                    refreshTotals(data);
                })
                .catch(() => { showToast("Couldn't update your cart", 'error'); haptic('error'); })
                .finally(() => setBusy(form, false));
        };

        minusBtn.addEventListener('click', (e) => {
            e.preventDefault();
            const current = parseInt(input.value, 10) || 1;
            if (current > 1) send(current - 1);
        });
        plusBtn.addEventListener('click', (e) => {
            e.preventDefault();
            const current = parseInt(input.value, 10) || 1;
            if (current < 20) send(current + 1);
        });
    });

    // Remove buttons: delete the line via fetch, then reload so the
    // (possibly empty) cart state and any server-side warnings re-render.
    document.querySelectorAll('form[data-cart-remove]').forEach(form => {
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            const itemId = form.querySelector('input[name="menuItemId"]')?.value;

            setBusy(form, true);
            const body = csrfParams();
            body.set('menuItemId', itemId);

            fetch(removeUrl, {
                method: 'POST',
                headers: { Accept: 'application/json' },
                body
            })
                .then(response => {
                    if (!response.ok) throw new Error('cart remove failed');
                    window.location.reload();
                })
                .catch(() => {
                    setBusy(form, false);
                    showToast("Couldn't remove that item", 'error');
                    haptic('error');
                });
        });
    });
}

function updateCartCount(count) {
    const badge = document.getElementById('cart-badge');
    if (badge) {
        badge.textContent = count > 99 ? '99+' : String(count);
        // Force the element out of the render tree and back in on the next frame so the
        // badge-pop keyframe animation restarts even if the badge was already visible.
        badge.style.display = 'none';
        requestAnimationFrame(() => {
            badge.style.display = count > 0 ? 'flex' : 'none';
        });
    }

    const bar = document.getElementById('sticky-cart-bar');
    const countLabel = document.getElementById('sticky-cart-count');
    if (bar && countLabel) {
        countLabel.textContent = count + (count === 1 ? ' item' : ' items');
        bar.style.display = count > 0 ? 'block' : 'none';
    }
}

/* ============================================================
   NAVBAR SCROLL SHADOW — adds subtle elevation on scroll
   ============================================================ */

function initNavbarScroll() {
    const navbar = document.querySelector('.navbar');
    if (!navbar) return;

    const onScroll = () => {
        if (window.scrollY > 12) {
            navbar.classList.add('has-scrolled');
        } else {
            navbar.classList.remove('has-scrolled');
        }
    };

    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
}

/* ============================================================
   TOAST — show a brief message at the bottom with icon
   ============================================================ */

/* `icon` is a Material Symbols glyph name, matching the customer app's icon set. */
/* ============================================================
   HAPTICS

   One place that decides what the phone is allowed to do, so every buzz in the product
   means the same thing and none of them are guesses.

   What actually works, as of 2026: the Vibration API is Chrome, Edge, Opera and Samsung
   Internet on Android. Safari never shipped it on iOS or macOS, and Firefox removed it in
   129. There are polyfills that unlock it on iOS through a hidden switch-element trick;
   this app does not use one. A payment flow is not the place for a hack that depends on
   undocumented Safari behaviour, and silence is a perfectly good fallback. iPhone users
   get the toast and the animation and no buzz.

   Three rules the patterns follow:

     * Durations are short. 10-15ms reads as a tick; anything past ~50ms reads as an
       alarm, and an alarm for "added to cart" is why people turn haptics off.
     * Only on a gesture the user just made. The spec requires sticky activation, so a
       vibration fired from a poll or a page load is dropped anyway — and would be
       intrusive even if it were not. Nothing here fires from the status watcher.
     * prefers-reduced-motion turns it off. The setting is not only about things moving
       on screen; it is the signal people with vestibular and sensory sensitivities
       actually set, and a buzz is a physical event.
   ============================================================ */
const HAPTIC_PATTERNS = {
    /* A selection landed: add to cart, quantity change. Barely perceptible on purpose. */
    tap: 10,
    /* Something completed. Two short ticks, which reads as "done" rather than "alert". */
    success: [15, 40, 15],
    /* Something needs attention but is not broken. */
    warning: [25, 50, 25],
    /* Something failed and the user has to do something about it. */
    error: [40, 60, 40, 60, 40],
};

function haptic(kind) {
    if (!('vibrate' in navigator)) return;
    try {
        if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
        navigator.vibrate(HAPTIC_PATTERNS[kind] || HAPTIC_PATTERNS.tap);
    } catch (e) {
        /* Some browsers throw rather than no-op when the page lacks activation. Never let
           a decorative buzz break the action it was decorating. */
    }
}

function showToast(message, icon = 'check_circle') {
    let container = document.querySelector('.toast-container');
    if (!container) {
        container = document.createElement('div');
        container.className = 'toast-container';
        /* Every status message in this app arrives as a toast, and without a live region
           none of them existed for anyone using a screen reader — "Added to cart",
           "Couldn't update your cart" and the rest were purely visual. polite rather than
           assertive so it waits for a pause instead of cutting across what is being read;
           none of these are urgent enough to interrupt. The region is on the container and
           created once, because a live region added to the page at the same moment as its
           content is frequently missed. */
        container.setAttribute('role', 'status');
        container.setAttribute('aria-live', 'polite');
        container.setAttribute('aria-atomic', 'true');
        document.body.appendChild(container);
    }

    const toast = document.createElement('div');
    toast.className = 'toast-msg';

    const glyph = document.createElement('span');
    glyph.className = 'material-symbols-outlined is-filled';
    glyph.style.color = 'var(--color-primary-soft)';
    glyph.style.fontSize = '1.15rem';
    /* Decorative — the text beside it already says what happened, and announcing the
       ligature name would read the word "check_circle" aloud. */
    glyph.setAttribute('aria-hidden', 'true');
    glyph.textContent = icon;

    const label = document.createElement('span');
    /* textContent, not innerHTML. Some of these messages come from the server (the
       add-to-cart failure passes data.message straight through), and building markup out
       of a string that came off the wire is how an injection gets in later even when
       today's callers are safe. */
    label.textContent = message;

    toast.append(glyph, ' ', label);
    container.appendChild(toast);

    setTimeout(() => toast.remove(), 2600);
}

/* ============================================================
   CATEGORY CHIPS — smooth scroll to the matching category section.
   Chips are generated server-side from each outlet's real category
   names (see student/menu.html), so this reads data-category rather
   than assuming a fixed set like "meals"/"snacks".
   ============================================================ */

function initCategoryChips() {
    const container = document.getElementById('category-chips');
    if (!container) return;

    const chips = () => container.querySelectorAll('.category-chip');

    const setActive = (chip) => {
        chips().forEach(c => {
            c.classList.toggle('active', c === chip);
            c.classList.toggle('is-scroll-active', c === chip);
        });
        // Keep the highlighted chip visible inside the horizontal strip.
        // Scroll only the strip's own scroll container — never the page.
        // scrollIntoView({inline:'center'}) can scroll the whole document
        // vertically to centre the chip, yanking the user back to the top.
        if (chip) {
            const strip = container.closest('.category-chips-wrapper') || container;
            const target = chip.offsetLeft - (strip.clientWidth - chip.offsetWidth) / 2;
            strip.scrollLeft = target;
        }
    };

    container.addEventListener('click', (e) => {
        const chip = e.target.closest('.category-chip');
        if (!chip) return;
        const categoryId = chip.dataset.category;

        const target = categoryId === 'all' ? container : document.getElementById(categoryId);
        if (target) {
            target.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }

        setActive(chip);
    });

    // While scrolling the menu, highlight the chip for whichever category
    // section is currently nearest the top of the viewport, so the pinned
    // chip strip always shows where you are.
    const sections = document.querySelectorAll('.menu-category-section[id]');
    if (!sections.length || !('IntersectionObserver' in window)) return;

    let scrollLock = null; // category id of a programmatic scroll in progress
    const visible = new Map(); // id -> intersection ratio

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            visible.set(entry.target.id, entry.isIntersecting ? entry.intersectionRatio : 0);
        });
        if (scrollLock) return; // don't fight a click-driven scroll

        let best = null;
        let bestRatio = 0;
        visible.forEach((ratio, id) => {
            if (ratio > bestRatio) { bestRatio = ratio; best = id; }
        });
        if (best) {
            setActive(container.querySelector(`.category-chip[data-category="${best}"]`));
        }
    }, { rootMargin: '-15% 0px -60% 0px', threshold: [0, 0.25, 0.5, 0.75, 1] });

    sections.forEach(s => observer.observe(s));

    // A click starts a programmatic scroll: suppress observer-driven
    // highlight changes until the target section actually arrives.
    container.addEventListener('click', () => {
        const activeChip = container.querySelector('.category-chip.active');
        scrollLock = activeChip?.dataset.category || null;
        const unlock = () => {
            scrollLock = null;
            window.removeEventListener('scrollend', unlock);
            clearTimeout(timer);
        };
        const timer = setTimeout(unlock, 1200);
        window.addEventListener('scrollend', unlock, { once: true });
    });
}

/**
 * The push invite on a live order.
 *
 * Deliberately not shown on load: the banner is rendered hidden and only revealed once we
 * know push is supported, the server has keys, permission has not already been refused,
 * and there is no subscription yet. Anything less and it would nag people who already said
 * yes, or offer something the browser cannot do.
 */
function initPushInvite() {
    const invite = document.getElementById('push-invite');
    if (!invite) return;
    if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) return;
    // Asking again after an explicit browser-level refusal cannot succeed, and dismissal
    // is remembered so this is an offer rather than a recurring interruption.
    if (Notification.permission === 'denied') return;
    if (localStorage.getItem('pushInviteDismissed') === '1') return;

    fetch('/api/push/public-key')
        .then((r) => (r.ok ? r.json() : null))
        .then((config) => {
            if (!config || !config.configured) return;
            return navigator.serviceWorker.ready
                .then((registration) => registration.pushManager.getSubscription())
                .then((sub) => {
                    if (sub) return;
                    invite.classList.remove('d-none');
                    invite.classList.add('d-flex');
                });
        })
        .catch(() => { /* Offline or blocked: stay hidden rather than offer a dead button. */ });

    document.getElementById('push-invite-yes').addEventListener('click', () => {
        // Reuses the same subscribe path as the account toggle, so there is one
        // implementation of enabling push and one place for it to go wrong.
        const proxy = { checked: true };
        haptic('tap');
        enablePush(proxy);
        invite.classList.add('d-none');
    });

    document.getElementById('push-invite-no').addEventListener('click', () => {
        try {
            localStorage.setItem('pushInviteDismissed', '1');
        } catch (e) { /* Private mode: forget the dismissal rather than fail the click. */ }
        invite.classList.add('d-none');
    });
}

/**
 * Keeps the order page honest while a student watches it.
 *
 * The status here is cooked in a kitchen, not in the browser, so a server-rendered page is
 * out of date the moment the canteen touches it. Rather than repaint the badge, the
 * medallion, the pickup code and the wording from JavaScript — four things that would then
 * have two implementations and drift — this reloads once the server reports a different
 * status. The reload is the cheap, always-correct option on a page nobody is typing into.
 */
function initOrderStatusWatch() {
    const host = document.querySelector('[data-order-id][data-order-status]');
    if (!host) return;

    const orderId = host.dataset.orderId;
    let rendered = host.dataset.orderStatus;
    /* COMPLETED and CANCELLED never change again, so polling them is pure noise.
       EXPIRED is deliberately not in that list: a payment captured after the timeout
       revives the order (OrderService.confirmPayment), and a student watching the order
       they just paid for is exactly who needs to see that happen. Leaving it out was an
       inconsistency between two changes made an hour apart. */
    if (['COMPLETED', 'CANCELLED'].includes(rendered)) return;

    const POLL_MS = 10000;

    function check() {
        fetch('/api/orders/' + encodeURIComponent(orderId) + '/status', {
            headers: { Accept: 'application/json' },
            credentials: 'same-origin',
        })
            .then((r) => (r.ok ? r.json() : null))
            .then((data) => {
                if (!data || data.status === rendered) return;
                const previous = rendered;
                rendered = data.status;
                clearInterval(timer);
                // Announce before reloading: a page that changes under someone with no
                // explanation is worse than one that changes slightly later.
                if (data.status === 'READY_FOR_PICKUP') {
                    showToast('Your order is ready — show your code at the counter');
                } else if (rendered === 'PAID' && previous === 'EXPIRED') {
                    /* The late-capture case. Being told "order updated" after watching an
                       order expire is not an explanation. */
                    showToast('Your payment came through — the order is back on');
                } else {
                    showToast('Order updated');
                }
                setTimeout(() => window.location.reload(), 900);
            })
            .catch(() => { /* Offline: keep what is on screen and try again next tick. */ });
    }

    const timer = setInterval(check, POLL_MS);
    // Coming back to a backgrounded tab is when the page is most likely to be stale, and a
    // throttled timer may not have fired for minutes.
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') check();
    });
}

/**
 * Offline state.
 *
 * The service worker serves a cached page for navigations when the network is gone, which
 * makes the app look offline-capable — but a POST is network-only, always was, and always
 * will be: you cannot queue a checkout and replay it later without risking charging
 * someone twice. So the honest behaviour is to say so before the tap, not after the
 * failure. A bar at the top, and the buttons that need the network go inert.
 */
function initOfflineState() {
    const NETWORK_BUTTON_SELECTOR = '#pay-btn, [data-needs-network]';

    let bar = null;
    function ensureBar() {
        if (bar) return bar;
        bar = document.createElement('div');
        bar.className = 'offline-bar';
        bar.setAttribute('role', 'status');
        bar.setAttribute('aria-live', 'polite');
        bar.textContent = 'You are offline. You can look around, but ordering needs a connection.';
        document.body.prepend(bar);
        return bar;
    }

    function apply() {
        const offline = !navigator.onLine;
        ensureBar().classList.toggle('is-visible', offline);
        document.querySelectorAll(NETWORK_BUTTON_SELECTOR).forEach((el) => {
            /* Only ever re-enable what this function disabled. The pay button disables
               itself while a payment is confirming, and coming back online must not undo
               that and let someone pay twice. */
            if (offline) {
                if (!el.disabled) {
                    el.disabled = true;
                    el.dataset.offlineDisabled = '1';
                }
            } else if (el.dataset.offlineDisabled === '1') {
                el.disabled = false;
                delete el.dataset.offlineDisabled;
            }
        });
    }

    window.addEventListener('online', apply);
    window.addEventListener('offline', apply);
    if (!navigator.onLine) apply();
}
