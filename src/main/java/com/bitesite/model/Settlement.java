package com.bitesite.model;

import java.math.BigDecimal;

/**
 * One canteen's money for a period: what students paid it, what the platform kept, and
 * what is therefore owed.
 *
 * <p>Every figure is a sum of what individual orders recorded, never a recalculation from
 * current rates — see {@link com.bitesite.service.SettlementService}.
 *
 * @param foodTotal    what students paid for food; the canteen's gross
 * @param commission   the platform's cut, at the rates in force when each order was placed
 * @param netPayable   foodTotal minus commission: what the platform owes this canteen
 * @param platformFees fees the platform charged students; never the canteen's money
 * @param tips         voluntary contributions; never the canteen's money either
 * @param collected    everything that actually moved through the gateway for this canteen
 */
public record Settlement(
        Long outletId,
        String outletName,
        Long tenantId,
        String collegeName,
        int orderCount,
        BigDecimal foodTotal,
        BigDecimal commission,
        BigDecimal netPayable,
        BigDecimal platformFees,
        BigDecimal tips,
        /** Discounts the platform paid for: the canteen was settled in full on these. */
        BigDecimal platformDiscounts,
        BigDecimal collected) {

    /** The blended rate actually charged over the period, which is not necessarily the
     *  canteen's current rate if it changed partway through. */
    public BigDecimal effectivePercent() {
        if (foodTotal == null || foodTotal.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return commission.multiply(new BigDecimal("100"))
                .divide(foodTotal, 2, java.math.RoundingMode.HALF_UP);
    }
}
