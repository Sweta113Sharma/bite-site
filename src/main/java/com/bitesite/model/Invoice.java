package com.bitesite.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A student's bill, broken down the way a bill should be.
 *
 * <p>Built entirely from what the order recorded at the time it was placed, never from
 * today's settings. Reopening a six-month-old order shows the six-month-old bill, which is
 * the only honest thing an invoice can do.
 *
 * <p><b>The tax maths.</b> Menu prices are GST-inclusive, so the tax is not added on top —
 * it is already inside the number the student saw, and the invoice's job is to say how
 * much of it was tax. Backing it out is {@code total × rate ÷ (100 + rate)}: at 5%, ₹105
 * contains ₹5 of tax, not ₹5.25. Getting this backwards overstates the tax on every line
 * of every bill.
 *
 * <p>A campus canteen and its students are always in one state, so the tax splits evenly
 * into CGST and SGST. IGST would need an inter-state supply, which does not happen when
 * somebody walks to a counter.
 *
 * <p><b>A discount changes the tax.</b> GST is charged on what was actually received, so
 * the taxable value is worked out on the food total <em>after</em> the discount, not
 * before. Taxing the pre-discount price would overstate the tax on every discounted order.
 *
 * <p><b>Whose invoice this is.</b> The canteen is the registered seller and the platform
 * is its agent, so this carries the canteen's legal name and GSTIN. The platform fee is
 * the platform's own charge to the student and is deliberately listed outside the food
 * total, because it is not part of the canteen's supply.
 */
public record Invoice(
        String sellerName,
        String sellerGstin,
        List<Line> lines,
        BigDecimal foodTotal,
        String promoCode,
        BigDecimal discount,
        BigDecimal taxableValue,
        BigDecimal cgst,
        BigDecimal sgst,
        BigDecimal gstPercent,
        boolean showTaxBreakdown,
        String taxNote,
        String feeLabel,
        BigDecimal feeCharged,
        BigDecimal feeStruckThrough,
        BigDecimal tip,
        BigDecimal total,
        String footer) {

    /** One item as billed: the name and price are the snapshot taken when it was ordered. */
    public record Line(String name, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}

    /** True when the bill should show a price with a line through it and the word FREE. */
    public boolean feeIsFree() {
        return feeStruckThrough != null
                && feeStruckThrough.compareTo(BigDecimal.ZERO) > 0
                && (feeCharged == null || feeCharged.compareTo(BigDecimal.ZERO) == 0);
    }

    public boolean hasDiscount() {
        return discount != null && discount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean hasTip() {
        return tip != null && tip.compareTo(BigDecimal.ZERO) > 0;
    }

    /** The tax contained in a GST-inclusive amount. Never adds anything on top. */
    public static BigDecimal taxWithin(BigDecimal inclusiveAmount, BigDecimal ratePercent) {
        if (inclusiveAmount == null || ratePercent == null
                || ratePercent.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return inclusiveAmount
                .multiply(ratePercent)
                .divide(new BigDecimal("100").add(ratePercent), 2, RoundingMode.HALF_UP);
    }

    /** What the amount was worth before that tax — the taxable value on the invoice. */
    public static BigDecimal valueWithin(BigDecimal inclusiveAmount, BigDecimal ratePercent) {
        if (inclusiveAmount == null) {
            return BigDecimal.ZERO;
        }
        return inclusiveAmount.subtract(taxWithin(inclusiveAmount, ratePercent));
    }
}
