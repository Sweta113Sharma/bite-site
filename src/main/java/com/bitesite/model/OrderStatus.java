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
            case PAYMENT_FAILED -> next == AWAITING_PAYMENT || next == CANCELLED;
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
}
