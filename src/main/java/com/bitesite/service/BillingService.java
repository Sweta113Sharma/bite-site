package com.bitesite.service;

import com.bitesite.dao.PlatformSettingsDao;
import com.bitesite.model.BillingSettings;
import com.bitesite.model.Invoice;
import com.bitesite.model.Order;
import com.bitesite.model.OrderItem;
import com.bitesite.model.Outlet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * What an order costs, who owes whom, and what the bill says.
 *
 * <p>Two jobs that must not be confused. {@link #charges} decides the terms at the moment
 * of checkout, and those get frozen onto the order row. {@link #invoiceFor} reads that
 * frozen record back. Nothing downstream of checkout ever consults live settings, so
 * changing the commission tomorrow cannot restate what a student was charged today or
 * what a canteen is owed for last month.
 */
@Service
@RequiredArgsConstructor
public class BillingService {

    private final PlatformSettingsDao platformSettingsDao;

    public BillingSettings settings() {
        return BillingSettings.from(platformSettingsDao.findAll());
    }

    /**
     * The commission for one canteen: its own negotiated rate, or the platform default
     * when it has none.
     *
     * <p>The override is null rather than a copied-down number precisely so that moving
     * the default moves every canteen that never negotiated, and leaves alone every
     * canteen that did.
     */
    public BigDecimal commissionPercentFor(Outlet outlet, BillingSettings settings) {
        return outlet != null && outlet.getCommissionPercent() != null
                ? outlet.getCommissionPercent()
                : settings.commissionPercent();
    }

    /** The terms applied to one checkout, ready to be written onto the order and never recomputed. */
    public record Charges(
            BigDecimal foodAmount,
            BigDecimal discount,
            String discountFundedBy,
            BigDecimal platformFee,
            BigDecimal platformFeeShown,
            BigDecimal tip,
            BigDecimal commissionPercent,
            BigDecimal commissionAmount,
            BigDecimal gstPercent,
            BigDecimal total) {}

    /**
     * Works out what to charge, and what the platform will be owed for it.
     *
     * <p>Commission is taken on the food only. The platform fee is the platform's own
     * charge and taking a cut of it would be charging itself; the tip was never the
     * canteen's money and must not enlarge what they are owed either.
     */
    public Charges charges(BigDecimal foodAmount, Outlet outlet, BigDecimal requestedTip) {
        return charges(foodAmount, outlet, requestedTip, null, null);
    }

    /**
     * @param discount  the promo discount, or null
     * @param fundedBy  who pays for it — this decides the commission base
     */
    public Charges charges(BigDecimal foodAmount, Outlet outlet, BigDecimal requestedTip,
            BigDecimal discount, String fundedBy) {
        BillingSettings s = settings();
        BigDecimal fee = s.feeCharged();
        BigDecimal tip = acceptableTip(requestedTip, s);
        BigDecimal off = discount == null ? BigDecimal.ZERO : discount;
        BigDecimal commissionPercent = commissionPercentFor(outlet, s);

        // The whole point of tracking who funds a discount. When the CANTEEN discounts its
        // own food its revenue genuinely fell, so commission follows it down. When the
        // PLATFORM funds the promotion the canteen sold at full price and is owed
        // commission on full price — the platform absorbs the difference out of its margin.
        // Getting this backwards makes canteens pay for the platform's marketing.
        BigDecimal commissionBase = "CANTEEN".equals(fundedBy) ? foodAmount.subtract(off) : foodAmount;
        BigDecimal commission = commissionBase
                .multiply(commissionPercent)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        return new Charges(
                foodAmount,
                off,
                off.compareTo(BigDecimal.ZERO) > 0 ? fundedBy : null,
                fee,
                s.platformFeeAmount(),
                tip,
                commissionPercent,
                commission,
                s.showGstBreakdown() ? s.gstPercent() : BigDecimal.ZERO,
                // What the student actually pays: the discount comes off the food.
                foodAmount.subtract(off).add(fee).add(tip));
    }

    /**
     * A tip is only accepted at an amount the panel offers.
     *
     * <p>The amounts are the operator's to set, so anything else arriving on a request is
     * either a typo or somebody editing the form — and neither should be able to decide
     * what a student is charged. Zero is always valid: this is optional by design.
     */
    public BigDecimal acceptableTip(BigDecimal requested, BillingSettings settings) {
        if (!settings.tipEnabled() || requested == null
                || requested.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return settings.tipAmounts().stream()
                .filter(offered -> offered.compareTo(requested) == 0)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    /**
     * The bill for an order, built from what that order recorded.
     *
     * <p>The seller is the canteen: it holds the registration, and the platform acts as its
     * agent. The platform fee sits outside the food total because it is not part of the
     * canteen's supply, and the tip sits outside both because it is not a sale at all.
     */
    public Invoice invoiceFor(Order order, List<OrderItem> items, Outlet outlet, BillingSettings settings) {
        BigDecimal food = order.getFoodAmount() != null ? order.getFoodAmount() : order.getTotalAmount();
        BigDecimal discount = order.getDiscountAmount() != null
                ? order.getDiscountAmount() : BigDecimal.ZERO;
        // The tax follows the money that actually changed hands. Working it out on the
        // pre-discount price would overstate the tax on every discounted order, and it is
        // the canteen's return that would carry the error.
        BigDecimal netFood = food.subtract(discount);
        BigDecimal gstPercent = order.getGstPercent() != null ? order.getGstPercent() : BigDecimal.ZERO;
        boolean showBreakdown = gstPercent.compareTo(BigDecimal.ZERO) > 0;

        BigDecimal tax = Invoice.taxWithin(netFood, gstPercent);
        // Intra-state every time: a student walks to the counter, so the supply never
        // crosses a state line and the tax is always half CGST, half SGST.
        BigDecimal half = tax.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);

        List<Invoice.Line> lines = items.stream()
                .map(i -> new Invoice.Line(i.getItemNameSnapshot(), i.getQuantity(),
                        i.getUnitPrice(), i.getSubtotal()))
                .toList();

        return new Invoice(
                outlet == null ? "" : (outlet.getLegalName() != null && !outlet.getLegalName().isBlank()
                        ? outlet.getLegalName() : outlet.getName()),
                outlet == null ? null : outlet.getGstin(),
                lines,
                food,
                order.getPromoCode(),
                discount,
                Invoice.valueWithin(netFood, gstPercent),
                half,
                // The other half absorbs any rounding remainder, so the two halves and the
                // taxable value always add back to what was charged.
                tax.subtract(half),
                gstPercent,
                showBreakdown,
                settings.gstNote(),
                settings.platformFeeLabel(),
                order.getPlatformFee(),
                order.getPlatformFeeShown(),
                order.getTipAmount(),
                order.getTotalAmount(),
                settings.invoiceFooter());
    }
}
