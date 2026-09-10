package com.bitesite.model;

public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    /**
     * Claimed for refunding, outcome not yet known.
     *
     * <p>Written and committed BEFORE the gateway is called, which is the whole point: a
     * refund is a network call that can succeed while telling you it failed. If we only
     * recorded the outcome afterwards, a timeout would roll the row back to CAPTURED, the
     * money would be gone, and the next cancel would cheerfully refund it again.
     *
     * <p>A payment sitting here means "we asked, we do not know". It is never refunded a
     * second time from this state, and it is flagged for reconciliation so a human resolves
     * it against Razorpay rather than the system guessing.
     */
    REFUND_PENDING,
    FAILED,
    REFUNDED
}
