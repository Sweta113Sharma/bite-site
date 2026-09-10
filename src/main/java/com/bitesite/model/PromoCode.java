package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * An order-level discount code.
 *
 * <p><b>{@link #fundedBy} is the field that matters.</b> A discount comes out of somebody's
 * pocket, and which pocket decides the payout: a canteen-funded discount genuinely lowers
 * that canteen's revenue, while a platform-funded one must leave the canteen paid in full
 * with the platform absorbing the difference. Getting this wrong makes canteens
 * unknowingly pay for the platform's marketing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromoCode {

    public enum Type { FLAT, PERCENT }

    public enum Funder {
        /** The platform pays: the canteen is settled on the full, undiscounted amount. */
        PLATFORM,
        /** The canteen pays: its revenue and the commission on it both fall. */
        CANTEEN
    }

    private Long id;
    private String code;
    private String description;
    private Type discountType;
    private BigDecimal discountValue;

    /** Ceiling on a percentage discount. Without one, a percentage is an open cheque. */
    private BigDecimal maxDiscount;
    private BigDecimal minOrderValue;
    private Funder fundedBy;

    /** Null means every college; null outlet means every canteen within that scope. */
    private Long tenantId;
    private Long outletId;

    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private Integer maxRedemptions;
    private Integer maxPerUser;
    private boolean active;
    private LocalDateTime createdAt;

    /** Populated on listings, counted from the redemption rows rather than a stored counter. */
    private int redemptionCount;

    /**
     * The discount this code produces on an order, never more than the order itself.
     *
     * <p>Capped at the food total on purpose: a ₹100 flat code on a ₹40 order is a ₹40
     * discount, not a ₹60 refund. Letting it exceed the order would turn a promotion into
     * a payout.
     */
    public BigDecimal discountOn(BigDecimal foodAmount) {
        if (foodAmount == null || foodAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount = discountType == Type.PERCENT
                ? foodAmount.multiply(discountValue).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                : discountValue;

        if (maxDiscount != null && discount.compareTo(maxDiscount) > 0) {
            discount = maxDiscount;
        }
        return discount.min(foodAmount).setScale(2, RoundingMode.HALF_UP);
    }

    /** Whether this code is even offered for this canteen. */
    public boolean appliesTo(Long orderTenantId, Long orderOutletId) {
        if (tenantId != null && !tenantId.equals(orderTenantId)) {
            return false;
        }
        return outletId == null || outletId.equals(orderOutletId);
    }

    public boolean withinWindow(LocalDateTime now) {
        if (validFrom != null && now.isBefore(validFrom)) {
            return false;
        }
        return validUntil == null || !now.isAfter(validUntil);
    }

    public boolean meetsMinimum(BigDecimal foodAmount) {
        return minOrderValue == null || foodAmount.compareTo(minOrderValue) >= 0;
    }
}
