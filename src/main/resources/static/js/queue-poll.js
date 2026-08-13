(function () {
    'use strict';

    const POLL_INTERVAL_MS = 5000;
    const container = document.getElementById('queue-body');
    const emptyNotice = document.getElementById('queue-empty');
    if (!container) {
        return;
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
        PAID: { label: 'Start preparing', next: 'PREPARING', btnClass: 'btn-warning' },
        PREPARING: { label: 'Mark ready', next: 'READY_FOR_PICKUP', btnClass: 'btn-primary' },
        READY_FOR_PICKUP: { label: 'Mark picked up', next: 'COMPLETED', btnClass: 'btn-success' }
    };

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
                <button type="submit" class="btn btn-sm ${action.btnClass} w-100">${action.label}</button>
            </form>` : '';

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
                    </div>
                </div>
            </div>`;
    }

    async function poll() {
        try {
            const response = await fetch('/api/orders/queue', { headers: { Accept: 'application/json' } });
            if (!response.ok) {
                return;
            }
            const orders = await response.json();
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
