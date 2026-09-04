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
            // A capture that lands after the payment sweeper is the one way out of
            // EXPIRED. Nothing in the UI offers this move: it exists so a student whose
            // bank was slow gets the food they paid for, instead of an expired order and
            // a silent charge. Every other exit from a terminal state stays closed.
            case EXPIRED -> next == PAID;
            case COMPLETED, CANCELLED -> false;
        };
    }

    /** Orders only enter the canteen's kitchen queue once payment is confirmed. */
    public boolean isKitchenVisible() {
        return this == PAID || this == PREPARING || this == READY_FOR_PICKUP;
    }

    /**
     * Position on the happy path — placed, paid, cooking, ready, collected — or -1 for a
     * status that never reaches it.
     *
     * <p>Exists so the order page can draw a progress rail without a chain of ternaries in
     * the template deciding which step is behind, which is current and which is still to
     * come. A cancelled or expired order is deliberately off the path: showing it as
     * "stalled at step two" would imply it is still moving.
     */
    public int progressStep() {
        return switch (this) {
            case AWAITING_PAYMENT -> 0;
            case PAID -> 1;
            case PREPARING -> 2;
            case READY_FOR_PICKUP -> 3;
            case COMPLETED -> 4;
            case PAYMENT_FAILED, EXPIRED, CANCELLED -> -1;
        };
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
