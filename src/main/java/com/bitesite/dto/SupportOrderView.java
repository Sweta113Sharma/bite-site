package com.bitesite.dto;

import com.bitesite.model.Order;
import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;

/**
 * One row on the support desk: the order, its payment if there is one, and the two names
 * a human needs to confirm they are looking at the right thing before touching money.
 *
 * <p>{@code payment} is null for orders that never reached checkout.
 */
public record SupportOrderView(Order order, Payment payment, String studentName, String collegeName) {

    /** True when there is captured money that has not already been sent back. */
    public boolean refundable() {
        return payment != null && payment.getStatus() == PaymentStatus.CAPTURED;
    }

    public boolean refunded() {
        return payment != null && payment.getStatus() == PaymentStatus.REFUNDED;
    }
}
