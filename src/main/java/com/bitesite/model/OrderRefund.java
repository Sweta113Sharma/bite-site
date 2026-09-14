package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One partial refund: money paid back for lines the kitchen took off a paid order.
 *
 * <p>Full refunds still live on the payment row (REFUND_PENDING / REFUNDED). This exists
 * because a payment can now carry several refunds, and each needs its own outcome: one can
 * be confirmed while another's HTTP call timed out.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRefund {

    public enum Status {
        /** Claimed and committed; the gateway has not confirmed it. */
        PENDING,
        /** The gateway accepted it, or its webhook said it was processed. */
        REFUNDED,
        /** The gateway said it failed. The money is still with us and a person must act. */
        FAILED
    }

    private Long id;
    private Long tenantId;
    private Long orderId;
    private Long paymentId;
    private BigDecimal amount;
    private Status status;
    private String reason;
    private String gatewayRefundId;
    private Long requestedBy;
    private LocalDateTime createdAt;
    private LocalDateTime settledAt;
}
