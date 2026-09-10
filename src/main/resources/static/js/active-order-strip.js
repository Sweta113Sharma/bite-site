(function () {
    'use strict';

    // The strip reports something that changes in a kitchen, not in the browser: an order
    // goes from paid to preparing to ready while the student is looking at the menu. Server
    // rendering alone meant it only told the truth at page load, so it sat on "Being
    // prepared" after collection and stayed on screen for orders that had finished.
    const POLL_INTERVAL_MS = 15000;

    const host = document.getElementById('active-order-strip-host');
    if (!host) {
        return;  // Not a signed-in student — no strip on this page, nothing to keep fresh.
    }

    // Signature of what is currently drawn. Re-rendering only on a real change keeps the
    // DOM (and the pulse animation) untouched during the long stretches where nothing
    // happens, which is most of them.
    let rendered = signatureOf(host.querySelector('.active-order-strip'));

    function signatureOf(el) {
        return el ? el.dataset.signature || '' : '';
    }

    function escapeHtml(value) {
        const div = document.createElement('div');
        div.textContent = value == null ? '' : value;
        return div.innerHTML;
    }

    function render(strip) {
        if (!strip) {
            host.innerHTML = '';
            rendered = '';
            return;
        }
        // Same field order the server uses in ActiveOrderStripView#signature(), so the
        // markup it rendered compares equal to the JSON describing the same state.
        const signature = [strip.orderId, strip.variant, strip.label, strip.tokenNo,
                           strip.moreCount, strip.cta].join('|');
        if (signature === rendered) {
            return;
        }
        const more = strip.moreCount > 0
            ? `<span> · +${strip.moreCount} more</span>`
            : '';
        host.innerHTML = `
            <a class="active-order-strip ${escapeHtml(strip.variant)}"
               data-signature="${escapeHtml(signature)}"
               href="/student/orders/${encodeURIComponent(strip.orderId)}">
                <span class="active-order-strip__pulse" aria-hidden="true"></span>
                <span class="active-order-strip__text">
                    <strong>${escapeHtml(strip.label)}</strong>
                    <span class="active-order-strip__meta">
                        <span>${escapeHtml(strip.tokenNo)}</span>${more}
                    </span>
                </span>
                <span class="active-order-strip__cta">
                    <span>${escapeHtml(strip.cta)}</span>
                    <span class="material-symbols-outlined">arrow_forward</span>
                </span>
            </a>`;
        rendered = signature;
    }

    async function refresh() {
        try {
            const response = await fetch('/api/orders/active', {
                headers: { 'Accept': 'application/json' },
                credentials: 'same-origin'
            });
            if (!response.ok) {
                // A 401/403 means the session ended. Stop rather than hammer the endpoint
                // with requests that cannot succeed until the user signs in again.
                if (response.status === 401 || response.status === 403) {
                    stopped = true;
                }
                return false;
            }
            // The endpoint answers with `null` when nothing is live, which is what tells
            // the strip to disappear.
            render(await response.json());
            return true;
        } catch (e) {
            // Offline or a dropped request: leave whatever is on screen and try again on
            // the next tick rather than blanking a strip that is probably still correct.
            return false;
        }
    }

    /* Self-scheduling rather than setInterval. A student on a slow connection was being
       asked for this every fifteen seconds regardless of whether the last request had
       failed, whether the phone was offline, or whether the tab was even in front of
       them. The backoff doubles to two minutes on repeated failure and resets on the
       first success, so a dead connection costs a handful of requests rather than four
       every minute for as long as the page stays open. */
    const MAX_BACKOFF_MS = 120000;
    let backoff = 0;
    let stopped = false;
    let timer = null;

    function schedule(delay) {
        clearTimeout(timer);
        if (!stopped) timer = setTimeout(tick, delay);
    }

    async function tick() {
        if (document.hidden || navigator.onLine === false) {
            schedule(POLL_INTERVAL_MS);
            return;
        }
        const ok = await refresh();
        backoff = ok ? 0 : Math.min(backoff ? backoff * 2 : POLL_INTERVAL_MS, MAX_BACKOFF_MS);
        schedule(backoff || POLL_INTERVAL_MS);
    }

    schedule(POLL_INTERVAL_MS);

    // Coming back to a backgrounded tab is exactly when the strip is most likely to be
    // wrong, and a timer in a throttled tab may not have fired for minutes.
    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'visible') { backoff = 0; schedule(0); }
    });
    window.addEventListener('online', function () { backoff = 0; schedule(0); });
}());
