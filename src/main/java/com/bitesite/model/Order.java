package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    private Long id;
    private Long tenantId;
    private Long outletId;
    private Long userId;
    private String tokenNo;

    /** Four-digit code the student shows at the counter, issued when the order is marked
     * ready. Null until then, and never reused while another order at the same outlet is
     * still waiting on it. */
    private String pickupCode;
    private LocalDateTime pickupCodeIssuedAt;
    /** What the student was charged: food + platform fee + tip. */
    private BigDecimal totalAmount;

    // ---- the terms this order was placed on, frozen at checkout ----
    //
    // Read back by the invoice and the settlement report; never recomputed from live
    // settings, so renegotiating a commission or ending the free-fee period cannot
    // restate a bill somebody already paid.
    private BigDecimal foodAmount;
    private BigDecimal platformFee;
    private BigDecimal platformFeeShown;
    private BigDecimal tipAmount;
    private BigDecimal commissionPercent;
    private BigDecimal commissionAmount;
    private BigDecimal gstPercent;

    /** The code used, its value, and who paid for it. Snapshotted like every other term,
     *  so editing or deleting a campaign never restates a bill or a payout. */
    private String promoCode;
    private BigDecimal discountAmount;
    private String discountFundedBy;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private LocalDateTime readyAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;

    /**
     * Why the order was cancelled, in words the student is shown on their order page.
     * Staff pick from a short list or type their own — see the outlet queue screen.
     */
    private String cancellationReason;

    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();
}
