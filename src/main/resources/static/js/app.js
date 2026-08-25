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
    initNavbarToggle();
    initNavbarScroll();
    registerServiceWorker();
    initPushToggle();
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
                .then(() => showToast('Notifications enabled'))
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
   NAVBAR TOGGLE — hamburger menu for staff/admin roles on mobile
   (the student portal has the bottom nav instead, so this button
   only renders for CANTEEN_STAFF / SUPER_ADMIN / TECH_MANAGER).
   ============================================================ */

function initNavbarToggle() {
    const btn = document.getElementById('navbar-toggle-btn');
    const menu = document.getElementById('navbar-links-collapse');
    if (!btn || !menu) return;

    btn.addEventListener('click', () => {
        const isOpen = menu.classList.toggle('is-open');
        btn.setAttribute('aria-expanded', String(isOpen));
    });

    // Close when a link inside is chosen, so the next page doesn't load with the menu open.
    menu.addEventListener('click', (e) => {
        if (e.target.closest('a')) {
            menu.classList.remove('is-open');
            btn.setAttribute('aria-expanded', 'false');
        }
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
            // Swap to filled icon for active tab
            const icon = item.querySelector('i');
            if (icon) {
                icon.className = icon.className.replace(' ph-', ' ph-fill ph-');
            }
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
                        showToast(data.message || 'That item can\'t be added right now.',
                            'ph-fill ph-warning-circle');
                        return;
                    }
                    const control = form.closest('.cart-control');
                    if (control) {
                        setControlQuantity(control, 1);
                    }
                    showToast('Added to cart');
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
                .catch(() => showToast("Couldn't update your cart"));
        };

        stepper.querySelector('.cart-qty-minus')
            ?.addEventListener('click', (e) => { e.preventDefault(); change(-1); });
        stepper.querySelector('.cart-qty-plus')
            ?.addEventListener('click', (e) => { e.preventDefault(); change(1); });
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

function showToast(message, iconClass = 'ph-fill ph-check-circle') {
    let container = document.querySelector('.toast-container');
    if (!container) {
        container = document.createElement('div');
        container.className = 'toast-container';
        document.body.appendChild(container);
    }

    const toast = document.createElement('div');
    toast.className = 'toast-msg';
    toast.innerHTML = `<i class="${iconClass}" style="color:var(--color-primary-soft); font-size:1.15rem;"></i> <span>${message}</span>`;
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

    container.addEventListener('click', (e) => {
        const chip = e.target.closest('.category-chip');
        if (!chip) return;
        const categoryId = chip.dataset.category;

        const target = categoryId === 'all' ? container : document.getElementById(categoryId);
        if (target) {
            target.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }

        container.querySelectorAll('.category-chip').forEach(c => {
            c.classList.toggle('active', c === chip);
        });
    });
}
