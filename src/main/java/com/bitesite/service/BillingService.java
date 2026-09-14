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
        BigDecimal commission = commissionOn(foodAmount, off, fundedBy, commissionPercent);

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

    private static BigDecimal commissionOn(BigDecimal foodAmount, BigDecimal discount, String fundedBy,
            BigDecimal commissionPercent) {
        BigDecimal commissionBase = "CANTEEN".equals(fundedBy) ? foodAmount.subtract(discount) : foodAmount;
        return commissionBase
                .multiply(commissionPercent)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    /** An order's money after some of its lines came off, and what the student is owed. */
    public record Restatement(
            BigDecimal foodAmount,
            BigDecimal discountAmount,
            BigDecimal commissionAmount,
            BigDecimal totalAmount,
            BigDecimal refund) {}

    /**
     * Restates an order's frozen terms once lines worth {@code removedSubtotal} are taken
     * off it, for the kitchen removing items it cannot make.
     *
     * <p>The student gets back the removed food less that food's share of the discount. A
     * ₹50-off code on a ₹500 order was worth 10% of each line, so a ₹100 line comes back as
     * ₹90 and the ₹450 left keeps its ₹45 off. The platform fee and the tip stay: the order
     * is still being made and collected. They go back only if every line comes off, which
     * is a full cancellation and is not priced here.
     *
     * <p>Commission is recomputed from the order's own percentage and funder, never today's
     * settings, so the canteen is settled on what it actually sold under the terms it sold
     * it on. Legacy orders placed before commission was recorded keep their null.
     *
     * <p>Applying this twice in a row gives the same money as removing both lines at once,
     * give or take a paisa of rounding, because each step prices against what is left.
     *
     * @throws IllegalArgumentException if nothing, or everything, is being removed
     */
    public Restatement withoutLines(Order order, BigDecimal removedSubtotal) {
        BigDecimal food = order.getFoodAmount() != null ? order.getFoodAmount() : order.getTotalAmount();
        BigDecimal discount = order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO;
        if (removedSubtotal.signum() <= 0 || removedSubtotal.compareTo(food) >= 0) {
            throw new IllegalArgumentException(
                    "Removing " + removedSubtotal + " of " + food + " is not a partial removal");
        }

        BigDecimal discountShare = discount.signum() == 0 ? BigDecimal.ZERO
                : discount.multiply(removedSubtotal).divide(food, 2, RoundingMode.HALF_UP);
        BigDecimal newFood = food.subtract(removedSubtotal);
        BigDecimal newDiscount = discount.subtract(discountShare);
        // Rounding must never leave more discount than food for it to come off.
        if (newDiscount.compareTo(newFood) > 0) {
            discountShare = discountShare.add(newDiscount.subtract(newFood));
            newDiscount = newFood;
        }
        BigDecimal refund = removedSubtotal.subtract(discountShare);

        BigDecimal commission = order.getCommissionPercent() == null ? order.getCommissionAmount()
                : commissionOn(newFood, newDiscount, order.getDiscountFundedBy(), order.getCommissionPercent());

        return new Restatement(newFood, newDiscount, commission, order.getTotalAmount().subtract(refund), refund);
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

        // Only what is still on the order. The amounts above were restated when lines came
        // off, so listing a removed line would make the bill not add up.
        List<Invoice.Line> lines = items.stream()
                .filter(i -> !i.isCancelled())
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
