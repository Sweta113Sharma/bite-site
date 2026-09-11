package com.bitesite.dto;

import java.math.BigDecimal;

/**
 * One refund as the gateway describes it, whether read back from its API or delivered by
 * its webhook. Amount in rupees so it compares directly with {@code payments.amount}.
 *
 * <p>{@code status} is the gateway's own vocabulary: {@code pending} while it is being
 * processed, {@code processed} once it is final, {@code failed} if it could not be done.
 */
public record GatewayRefund(String id, BigDecimal amountRupees, String status) {

    public static GatewayRefund fromPaise(String id, long amountPaise, String status) {
        return new GatewayRefund(id, BigDecimal.valueOf(amountPaise, 2), status);
    }

    public boolean isProcessed() {
        return "processed".equals(status);
    }

    public boolean isPending() {
        return "pending".equals(status);
    }

    public boolean isFailed() {
        return "failed".equals(status);
    }
}
