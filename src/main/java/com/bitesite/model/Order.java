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
    private BigDecimal totalAmount;
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
