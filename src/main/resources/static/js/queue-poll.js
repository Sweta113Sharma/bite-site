(function () {
    'use strict';

    const POLL_INTERVAL_MS = 5000;
    const container = document.getElementById('queue-body');
    const emptyNotice = document.getElementById('queue-empty');
    if (!container) {
        return;
    }

    /* A kitchen tablet sits on a shelf. The queue re-rendered silently, so a new order
       arriving looked exactly like nothing happening and staff found out by glancing over.
       A short chime plus a count is the difference between a five-minute-old order and a
       fifteen-minute-old one.

       WebAudio rather than an audio file: no asset to ship, no 404 to debug, and nothing
       to cache-bust. Browsers refuse to make noise before the page has been interacted
       with, so the context is created lazily on the first interaction and the whole thing
       degrades to silence rather than an error if that never happens. */
    let audioContext = null;
    function unlockAudio() {
        if (audioContext) return;
        try {
            audioContext = new (window.AudioContext || window.webkitAudioContext)();
        } catch (e) {
            audioContext = null;
        }
    }
    document.addEventListener('click', unlockAudio, { once: true });
    document.addEventListener('keydown', unlockAudio, { once: true });

    function chime() {
        if (!audioContext) return;
        try {
            const osc = audioContext.createOscillator();
            const gain = audioContext.createGain();
            osc.connect(gain);
            gain.connect(audioContext.destination);
            osc.type = 'sine';
            osc.frequency.value = 880;
            /* Short and quiet. This fires in a room where people are working. */
            gain.gain.setValueAtTime(0.0001, audioContext.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.2, audioContext.currentTime + 0.01);
            gain.gain.exponentialRampToValueAtTime(0.0001, audioContext.currentTime + 0.35);
            osc.start();
            osc.stop(audioContext.currentTime + 0.36);
        } catch (e) {
            /* Audio is a courtesy, never a dependency of the queue working. */
        }
    }

    function announce(message) {
        const banner = document.getElementById('queue-alert');
        if (!banner) return;
        banner.textContent = message;
        banner.classList.remove('d-none');
        clearTimeout(banner.dataset.timer);
        banner.dataset.timer = setTimeout(() => banner.classList.add('d-none'), 6000);
    }

    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const csrfParam = document.querySelector('meta[name="_csrf_parameter"]').content;

    const STATUS_BADGE = {
        AWAITING_PAYMENT: 'bg-secondary',
        PAID: 'bg-info text-dark',
        PREPARING: 'bg-warning text-dark',
        READY_FOR_PICKUP: 'bg-primary',
        COMPLETED: 'bg-success'
    };

    const NEXT_ACTION = {
        PAID: { label: 'Start preparing', next: 'PREPARING', btnClass: 'btn-warning', icon: 'ph-cooking-pot' },
        PREPARING: { label: 'Mark ready', next: 'READY_FOR_PICKUP', btnClass: 'btn-primary', icon: 'ph-bell-simple-ringing' }
        // READY_FOR_PICKUP is absent on purpose: handover needs the student's pickup
        // code, so it renders a form below rather than a one-click status button.
    };

    const CANCEL_REASONS = [
        { value: 'Ingredients ran out', label: 'Ingredients ran out' },
        { value: 'Kitchen closing early', label: 'Kitchen closing early' },
        { value: 'Student asked to cancel', label: 'Student asked to cancel' },
        { value: 'Ordered by mistake', label: 'Ordered by mistake' },
        { value: '', label: 'No reason given' }
    ];

    function escapeHtml(value) {
        const div = document.createElement('div');
        div.textContent = value;
        return div.innerHTML;
    }

    function renderOrder(order) {
        const badgeClass = STATUS_BADGE[order.status] || 'bg-secondary';
        const items = order.itemSummaries.map(s => `<li>${escapeHtml(s)}</li>`).join('');
        const action = NEXT_ACTION[order.status];
        const actionHtml = action ? `
            <form method="post" action="/canteen/queue/${order.id}/status">
                <input type="hidden" name="newStatus" value="${action.next}"/>
                <input type="hidden" name="${csrfParam}" value="${csrfToken}"/>
                <button type="submit" class="btn btn-sm ${action.btnClass} w-100"><i class="ph ${action.icon}"></i>${action.label}</button>
            </form>` : '';
        // Kept in step with canteen/queue.html by hand: this renderer replaces the whole
        // queue body every poll, so anything only present in the Thymeleaf version would
        // vanish five seconds after the page loads. The reason list must match that file.
        const reasonOptions = CANCEL_REASONS
            .map(r => `<option value="${escapeHtml(r.value)}">${escapeHtml(r.label)}</option>`)
            .join('');
        // Mirrors the pickup form in canteen/queue.html. This renderer replaces the whole
        // queue body on every poll, so anything only present there would vanish five
        // seconds after the page loaded.
        const pickupHtml = order.status === 'READY_FOR_PICKUP' ? `
            <form method="post" action="/canteen/queue/${order.id}/collect" class="pickup-form">
                <label class="form-label small mb-1">Pickup code from the student's screen</label>
                <div class="d-flex gap-2">
                    <input type="text" name="pickupCode" class="form-control form-control-sm pickup-input"
                           inputmode="numeric" pattern="[0-9]*" maxlength="4" autocomplete="off"
                           placeholder="0000" aria-label="Pickup code"/>
                    <input type="hidden" name="${csrfParam}" value="${csrfToken}"/>
                    <button type="submit" class="btn btn-sm btn-success"><i class="ph ph-check-circle"></i>Hand over</button>
                </div>
            </form>` : '';

        const cancelHtml = order.status === 'PAID' ? `
            <details class="cancel-panel mt-2">
                <summary><i class="ph ph-x-circle"></i>Cancel &amp; refund</summary>
                <form method="post" action="/canteen/queue/${order.id}/cancel"
                      onsubmit="return confirm('Cancel this order and refund the customer in full?');">
                    <label class="form-label small mb-1">Reason (the student sees this)</label>
                    <select name="reason" class="form-select form-select-sm mb-2">${reasonOptions}</select>
                    <input type="hidden" name="${csrfParam}" value="${csrfToken}"/>
                    <button type="submit" class="btn btn-sm btn-danger w-100"><i class="ph ph-arrow-u-up-left"></i>Cancel and refund in full</button>
                </form>
            </details>` : '';

        return `
            <div class="col" data-order-id="${order.id}">
                <div class="card h-100">
                    <div class="card-body">
                        <div class="d-flex justify-content-between align-items-start mb-2">
                            <h5 class="token-badge m-0">${escapeHtml(order.tokenNo)}</h5>
                            <span class="badge ${badgeClass}">${order.status.replace(/_/g, ' ')}</span>
                        </div>
                        <ul class="small text-muted mb-3">${items}</ul>
                        ${actionHtml}
                        ${pickupHtml}
                        ${cancelHtml}
                    </div>
                </div>
            </div>`;
    }

    // Snapshot of what's currently rendered, so a poll that returns identical data doesn't
    // touch the DOM at all — a full innerHTML replace every 5s would re-trigger the card
    // entry animation on every card, forever, which reads as distracting flicker rather
    // than motion with purpose.
    let lastSnapshot = null;

    async function poll() {
        try {
            const response = await fetch('/api/orders/queue', { headers: { Accept: 'application/json' } });
            if (!response.ok) {
                return;
            }
            const orders = await response.json();
            const snapshot = JSON.stringify(orders.map(o => [o.id, o.status]));
            if (snapshot === lastSnapshot) {
                return;
            }
            /* Only orders that were not on the previous poll count as arrivals — a status
               change on an order already in the queue is not a new one, and chiming for it
               would train staff to ignore the sound. lastSnapshot is null on first load,
               so opening the page does not announce the whole existing queue. */
            const arrived = lastSnapshot === null ? 0
                : orders.filter(o => !lastSnapshot.includes('[' + o.id + ',')).length;
            lastSnapshot = snapshot;

            if (arrived > 0) {
                chime();
                announce(arrived === 1 ? 'New order in the queue' : arrived + ' new orders in the queue');
            }

            if (emptyNotice) {
                emptyNotice.style.display = orders.length === 0 ? '' : 'none';
            }
            container.innerHTML = orders.map(renderOrder).join('');
        } catch (err) {
            // Transient network hiccup — next poll will retry, no need to surface this.
        }
    }

    setInterval(poll, POLL_INTERVAL_MS);
})();
