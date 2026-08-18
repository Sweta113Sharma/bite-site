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
    initQuantitySteppers();
    initBodyClass();
    initAddToCartForms();
    initNavbarToggle();
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

    searchInput.addEventListener('input', () => {
        const query = searchInput.value.toLowerCase().trim();
        const cards = document.querySelectorAll('.menu-card');
        const categories = document.querySelectorAll('.menu-category-section');

        cards.forEach(card => {
            const name = card.dataset.name || card.querySelector('.menu-card-name')?.textContent || '';
            const match = !query || name.toLowerCase().includes(query);
            card.style.display = match ? '' : 'none';
        });

        // Hide empty category headers
        categories.forEach(section => {
            const visibleCards = section.querySelectorAll('.menu-card:not([style*="display: none"])');
            section.style.display = visibleCards.length > 0 ? '' : 'none';
        });
    });
}

/* ============================================================
   QUANTITY STEPPER — +/- buttons
   ============================================================ */

function initQuantitySteppers() {
    document.querySelectorAll('.qty-stepper').forEach(stepper => {
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
   TOAST — show a brief message at the bottom
   ============================================================ */

function showToast(message) {
    let container = document.querySelector('.toast-container');
    if (!container) {
        container = document.createElement('div');
        container.className = 'toast-container';
        document.body.appendChild(container);
    }

    const toast = document.createElement('div');
    toast.className = 'toast-msg';
    toast.textContent = message;
    container.appendChild(toast);

    setTimeout(() => toast.remove(), 2600);
}

/* ============================================================
   CATEGORY CHIP SCROLL — smooth scroll to category section
   ============================================================ */

function scrollToCategory(categoryId) {
    const section = document.getElementById(categoryId);
    if (section) {
        section.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    // Update active chip
    document.querySelectorAll('.category-chip').forEach(chip => {
        chip.classList.toggle('active', chip.dataset.category === categoryId);
    });
}
