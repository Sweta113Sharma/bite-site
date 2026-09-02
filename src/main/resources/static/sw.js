// BiteSite service worker.
//
// Strategy is deliberately conservative given what this app actually does — menu prices,
// availability, cart contents, and order status all change server-side and must never be
// served stale when the network is reachable:
//   - Static assets (CSS/JS/our own images/fonts): cache-first, since these are safe to
//     reuse across visits and rarely change.
//   - Page navigations (HTML): network-first — always prefer a live page; only fall back
//     to a cached copy (or the offline page) when the network genuinely fails.
//   - Everything else (POST requests, /api/**, third-party CDN requests): left untouched,
//     network-only. A service worker must never cache or replay a checkout/order POST.
'use strict';

// Bumped when the precache list changes: an existing client keeps its old list
// until the version changes.
const VERSION = 'v3';
const STATIC_CACHE = `bitesite-static-${VERSION}`;
const PAGE_CACHE = `bitesite-pages-${VERSION}`;
const OFFLINE_URL = '/offline.html';

// Only what the offline page itself needs.
//
// CSS and JS are deliberately NOT listed any more. They are served from content-hashed
// URLs now (see StaticResourceConfig), so a hardcoded path here would name a file the
// pages no longer request — and since cache.addAll() rejects wholesale if any single URL
// fails, one stale entry silently disables offline support for everyone. That had already
// happened: this list still named 03-app.css and friends long after they stopped being
// linked. Hashed assets are picked up by the runtime cache-first handler below instead,
// which is strictly better — an immutable URL can never serve a stale file, so it needs
// no list to maintain and no version bump to invalidate.
const PRECACHE_URLS = [
    OFFLINE_URL,
    '/img/logo-mark-pastel.svg',
];

self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(STATIC_CACHE)
            .then((cache) => cache.addAll(PRECACHE_URLS))
            .then(() => self.skipWaiting())
    );
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys()
            .then((keys) => Promise.all(
                keys.filter((key) => key !== STATIC_CACHE && key !== PAGE_CACHE)
                    .map((key) => caches.delete(key))
            ))
            .then(() => self.clients.claim())
    );
});

function isStaticAsset(url) {
    return url.origin === self.location.origin
        && (url.pathname.startsWith('/css/')
            || url.pathname.startsWith('/js/')
            || url.pathname.startsWith('/img/')
            // /fonts/ was missing, so the icon font — by far the largest asset in the
            // product — was the one thing the service worker never cached.
            || url.pathname.startsWith('/fonts/'));
}

self.addEventListener('fetch', (event) => {
    const request = event.request;
    if (request.method !== 'GET') {
        return; // never intercept POST/PUT/DELETE — checkout, cart, order actions pass straight through
    }

    const url = new URL(request.url);

    // Page navigations: network-first, cache the successful response for offline fallback,
    // and serve the offline page only when there's truly no cached copy either.
    if (request.mode === 'navigate') {
        event.respondWith(
            fetch(request)
                .then((response) => {
                    const copy = response.clone();
                    caches.open(PAGE_CACHE).then((cache) => cache.put(request, copy));
                    return response;
                })
                .catch(() =>
                    caches.match(request).then((cached) => cached || caches.match(OFFLINE_URL))
                )
        );
        return;
    }

    // Our own static assets: cache-first for fast repeat loads.
    if (isStaticAsset(url)) {
        event.respondWith(
            caches.match(request).then((cached) => {
                if (cached) return cached;
                return fetch(request).then((response) => {
                    const copy = response.clone();
                    caches.open(STATIC_CACHE).then((cache) => cache.put(request, copy));
                    return response;
                });
            })
        );
    }

    // Everything else (third-party CDN fonts/icons/Bootstrap, /api/**, /uploads/**) is left
    // to the network untouched.
});

// Order-ready / cancelled alerts — see PushNotificationService for what sends these.
self.addEventListener('push', (event) => {
    let data = { title: 'BiteSite', body: 'You have an update.' };
    try {
        if (event.data) data = event.data.json();
    } catch (e) {
        // Non-JSON payload — fall back to the default above rather than throw.
    }
    event.waitUntil(
        self.registration.showNotification(data.title, {
            body: data.body,
            icon: '/img/icons/icon-192.png',
            badge: '/img/icons/icon-192.png',
        })
    );
});

self.addEventListener('notificationclick', (event) => {
    event.notification.close();
    event.waitUntil(
        self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
            for (const client of clients) {
                if ('focus' in client) return client.focus();
            }
            if (self.clients.openWindow) return self.clients.openWindow('/student/orders');
        })
    );
});
