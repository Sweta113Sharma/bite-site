package com.bitesite.dto;

import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;

import java.util.List;

/**
 * Everything the sticky order strip draws, resolved once.
 *
 * <p>The strip is rendered twice — by Thymeleaf on first paint, and by the poller that
 * keeps it honest afterwards. Those two are the same four-way mapping from order status to
 * a label, a call to action and a colour, and keeping a copy in each language is how they
 * drift: a status added to the template but not the script leaves the strip showing the
 * previous state forever, which is the failure this whole view exists to fix.
 *
 * <p>So the mapping lives here. The template reads it through {@code T(...).from(...)} and
 * the API serialises the same record.
 */
public record ActiveOrderStripView(
        Long orderId,
        String variant,
        String label,
        String tokenNo,
        int moreCount,
        String cta) {

    /** Null when there is nothing live — the caller renders no strip at all. */
    public static ActiveOrderStripView from(List<Order> activeOrders) {
        if (activeOrders == null || activeOrders.isEmpty()) {
            return null;
        }
        // Already sorted by attention rank (see OrderService#liveForUser), so the one that
        // most needs the student is first.
        Order top = activeOrders.get(0);
        OrderStatus status = top.getStatus();
        return new ActiveOrderStripView(
                top.getId(),
                variantFor(status),
                labelFor(status),
                top.getTokenNo(),
                activeOrders.size() - 1,
                ctaFor(status));
    }

    /**
     * What is currently drawn, as one comparable string. The server stamps it onto the
     * markup and the poller recomputes it from the JSON, so an unchanged order is
     * recognised as unchanged and the DOM — and the pulse animation running in it — is
     * left alone. Without this the first poll after every page load would redraw a strip
     * that had not changed.
     */
    public String signature() {
        return orderId + "|" + variant + "|" + label + "|" + tokenNo + "|" + moreCount + "|" + cta;
    }

    private static String variantFor(OrderStatus status) {
        return switch (status) {
            case READY_FOR_PICKUP -> "is-ready";
            case PREPARING -> "is-preparing";
            case AWAITING_PAYMENT, PAYMENT_FAILED -> "is-unpaid";
            default -> "is-paid";
        };
    }

    private static String labelFor(OrderStatus status) {
        return switch (status) {
            case READY_FOR_PICKUP -> "Ready to collect";
            case PREPARING -> "Being prepared";
            case PAID -> "Order confirmed";
            case PAYMENT_FAILED -> "Payment failed";
            default -> "Payment pending";
        };
    }

    private static String ctaFor(OrderStatus status) {
        return switch (status) {
            case READY_FOR_PICKUP -> "Show code";
            case AWAITING_PAYMENT, PAYMENT_FAILED -> "Pay now";
            default -> "Track";
        };
    }
}
