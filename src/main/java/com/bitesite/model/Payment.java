package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {
    private Long id;
    private Long tenantId;
    private Long orderId;
    private String razorpayOrderId;
    private String razorpayPaymentId;
    private String razorpaySignature;
    private BigDecimal amount;
    private PaymentStatus status;

    /** Money we hold that no order is going to honour. Set when a capture arrives for an
     * order that cannot be revived; cleared by hand once refunded. */
    private boolean needsReconciliation;

    private String reconciliationReason;

    /** The LAST time the gateway was asked to refund this, not the first. Bumped by every
     * retry, which is what keeps two sweeps from sending the same refund together. */
    private LocalDateTime refundAttemptedAt;

    /** Who asked for the refund; null for the system. Carried so that a refund settled
     * later by the webhook or the sweep is still audited against the right person. */
    private Long refundRequestedBy;

    /** What to write on the order once the refund is confirmed. Null means the money goes
     * back and the order stays as it is (a refund of a COMPLETED order). */
    private String refundReason;

    private int refundAttempts;
    private LocalDateTime createdAt;
    private LocalDateTime verifiedAt;
}
