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
public class OrderItem {
    private Long id;
    private Long orderId;
    private Long menuItemId;
    private String itemNameSnapshot;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
    /** Set when the kitchen took this line off a paid order (see ItemCancellationService).
     * The line stays on the order so both sides can see what was removed and why. */
    private LocalDateTime cancelledAt;
    /** What the student is told, e.g. "Out of stock". */
    private String cancellationReason;
    /** The partial refund that paid this line back, or null when nothing was owed. */
    private Long refundId;

    public boolean isCancelled() {
        return cancelledAt != null;
    }
}
