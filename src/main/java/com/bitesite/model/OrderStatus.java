package com.bitesite.model;

/**
 * Server-side order state machine. No controller or DAO sets {@code status} directly —
 * every transition goes through {@link com.bitesite.service.OrderService}, which checks
 * {@link #canTransitionTo(OrderStatus)} first.
 */
public enum OrderStatus {
    AWAITING_PAYMENT,
    PAID,
    PREPARING,
    READY_FOR_PICKUP,
    COMPLETED,
    PAYMENT_FAILED,
    EXPIRED,
    CANCELLED;

    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case AWAITING_PAYMENT -> next == PAID || next == PAYMENT_FAILED || next == EXPIRED || next == CANCELLED;
            // EXPIRED included so the payment-timeout sweeper has a legal move: a failed
            // payment left alone is as dead as one never attempted.
            case PAYMENT_FAILED -> next == AWAITING_PAYMENT || next == CANCELLED || next == EXPIRED;
            case PAID -> next == PREPARING || next == CANCELLED;
            case PREPARING -> next == READY_FOR_PICKUP;
            case READY_FOR_PICKUP -> next == COMPLETED;
            case COMPLETED, EXPIRED, CANCELLED -> false;
        };
    }

    /** Orders only enter the canteen's kitchen queue once payment is confirmed. */
    public boolean isKitchenVisible() {
        return this == PAID || this == PREPARING || this == READY_FOR_PICKUP;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == EXPIRED || this == CANCELLED;
    }

    /**
     * An order the student still has something to do about, or is waiting on. The exact
     * complement of {@link #isTerminal()} — there is no third category, and defining it
     * that way means a new status cannot be quietly left out of the "active" surfaces.
     */
    public boolean isLive() {
        return !isTerminal();
    }

    /**
     * How urgently this state wants the student's attention, lowest first. Used to pick
     * which order the active-order strip shows when several are live at once: food going
     * cold on a counter beats one that has not been started, and an unpaid order beats
     * both because nothing happens at all until it is settled.
     */
    public int attentionRank() {
        return switch (this) {
            case READY_FOR_PICKUP -> 0;
            case PAYMENT_FAILED -> 1;
            case AWAITING_PAYMENT -> 2;
            case PREPARING -> 3;
            case PAID -> 4;
            default -> 99;
        };
    }
}
