package com.bitesite.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The platform's commercial settings, read once and handed round as a value.
 *
 * <p>Everything here is configurable and nothing is compiled in. The seeded defaults are
 * all inert on purpose — no commission, no fee, no tip, no tax breakdown — so the day this
 * shipped nothing about anybody's bill changed, and each control turns on only when
 * somebody deliberately turns it on.
 *
 * <p>A typed view over the key/value table rather than string lookups scattered through
 * services: a mistyped key here fails in one place, at startup of the request, instead of
 * silently reading as "off" somewhere deep in a payment path.
 *
 * @param commissionPercent   default cut, when a canteen has not negotiated its own
 * @param platformFeeAmount   flat rupees per order
 * @param chargePlatformFee   whether that amount is actually taken
 * @param showFeeWhenFree     show the amount struck through as FREE while it is not taken
 * @param platformFeeLabel    what the invoice calls it
 * @param showGstBreakdown    whether to split the inclusive price into its tax parts
 * @param gstPercent          the rate to back out of a GST-inclusive price
 * @param gstNote             the line shown when no breakdown is displayed
 * @param tipEnabled          whether to ask for a voluntary contribution at checkout
 * @param tipTitle            heading on that dialog
 * @param tipMessage          body copy on that dialog
 * @param tipAmounts          the suggested amounts offered
 * @param invoiceFooter       closing line on the invoice
 */
public record BillingSettings(
        BigDecimal commissionPercent,
        BigDecimal platformFeeAmount,
        boolean chargePlatformFee,
        boolean showFeeWhenFree,
        String platformFeeLabel,
        boolean showGstBreakdown,
        BigDecimal gstPercent,
        String gstNote,
        boolean tipEnabled,
        String tipTitle,
        String tipMessage,
        List<BigDecimal> tipAmounts,
        String invoiceFooter) {

    public static final String COMMISSION_PERCENT = "commission.default_percent";
    public static final String FEE_AMOUNT = "platform_fee.amount";
    public static final String FEE_CHARGE = "platform_fee.charge";
    public static final String FEE_SHOW_WHEN_FREE = "platform_fee.show_when_free";
    public static final String FEE_LABEL = "platform_fee.label";
    public static final String GST_SHOW = "gst.show_breakdown";
    public static final String GST_PERCENT = "gst.percent";
    public static final String GST_NOTE = "gst.note";
    public static final String TIP_ENABLED = "tip.enabled";
    public static final String TIP_TITLE = "tip.title";
    public static final String TIP_MESSAGE = "tip.message";
    public static final String TIP_AMOUNTS = "tip.amounts";
    public static final String INVOICE_FOOTER = "invoice.footer";

    /** Reads the stored map, falling back to inert values for anything absent. */
    public static BillingSettings from(Map<String, String> s) {
        return new BillingSettings(
                money(s.get(COMMISSION_PERCENT)),
                money(s.get(FEE_AMOUNT)),
                flag(s.get(FEE_CHARGE)),
                flag(s.get(FEE_SHOW_WHEN_FREE)),
                text(s.get(FEE_LABEL), "Platform fee"),
                flag(s.get(GST_SHOW)),
                money(s.get(GST_PERCENT)),
                text(s.get(GST_NOTE), "Prices are inclusive of all applicable taxes."),
                flag(s.get(TIP_ENABLED)),
                text(s.get(TIP_TITLE), "Buy the developer a coffee"),
                text(s.get(TIP_MESSAGE), ""),
                amounts(s.get(TIP_AMOUNTS)),
                text(s.get(INVOICE_FOOTER), ""));
    }

    /**
     * What the student is actually charged as a platform fee: the configured amount, or
     * nothing while it is switched off. The amount stays visible separately so the invoice
     * can strike it through — showing a price and charging zero is the whole point of the
     * free period.
     */
    public BigDecimal feeCharged() {
        return chargePlatformFee ? platformFeeAmount : BigDecimal.ZERO;
    }

    /** True when the invoice should show a struck-through fee rather than nothing at all. */
    public boolean showFreeFee() {
        return !chargePlatformFee && showFeeWhenFree
                && platformFeeAmount.compareTo(BigDecimal.ZERO) > 0;
    }

    private static BigDecimal money(String v) {
        if (v == null || v.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            // A malformed setting must not charge somebody an unpredictable amount.
            return BigDecimal.ZERO;
        }
    }

    private static boolean flag(String v) {
        return "true".equalsIgnoreCase(v == null ? null : v.trim());
    }

    private static String text(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /** "5,10,20" into offered amounts, skipping anything unparseable rather than failing. */
    private static List<BigDecimal> amounts(String v) {
        List<BigDecimal> out = new ArrayList<>();
        if (v == null || v.isBlank()) {
            return out;
        }
        for (String part : v.split(",")) {
            try {
                BigDecimal amount = new BigDecimal(part.trim());
                if (amount.compareTo(BigDecimal.ZERO) > 0) {
                    out.add(amount);
                }
            } catch (NumberFormatException ignored) {
                // One bad entry should not empty the whole list.
            }
        }
        return out;
    }
}
