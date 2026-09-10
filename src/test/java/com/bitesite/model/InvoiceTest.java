package com.bitesite.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tax arithmetic on every bill the platform prints. Wrong here means every student is
 * shown a tax figure that does not add up, on an invoice issued in a canteen's name
 * against their GSTIN.
 */
class InvoiceTest {

    // ---- backing tax out of a GST-inclusive price ---------------------------

    /**
     * The mistake this exists to prevent: 5% of ₹105 is ₹5.25, but ₹105 that already
     * includes 5% contains ₹5. Adding instead of extracting overstates tax on every line.
     */
    @Test
    void taxIsExtractedFromTheInclusivePriceNotAddedToIt() {
        assertThat(Invoice.taxWithin(new BigDecimal("105.00"), new BigDecimal("5")))
                .isEqualByComparingTo("5.00");
        assertThat(Invoice.valueWithin(new BigDecimal("105.00"), new BigDecimal("5")))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void theValueAndItsTaxAlwaysSumBackToWhatWasCharged() {
        for (String amount : List.of("20.00", "37.50", "99.99", "1250.00", "0.01")) {
            for (String rate : List.of("5", "12", "18")) {
                BigDecimal total = new BigDecimal(amount);
                BigDecimal r = new BigDecimal(rate);
                assertThat(Invoice.valueWithin(total, r).add(Invoice.taxWithin(total, r)))
                        .as("%s at %s%% must reconcile", amount, rate)
                        .isEqualByComparingTo(total);
            }
        }
    }

    @Test
    void aZeroRateExtractsNothingAndLeavesTheWholeAmountAsValue() {
        assertThat(Invoice.taxWithin(new BigDecimal("100.00"), BigDecimal.ZERO))
                .isEqualByComparingTo("0.00");
        assertThat(Invoice.valueWithin(new BigDecimal("100.00"), BigDecimal.ZERO))
                .isEqualByComparingTo("100.00");
    }

    /** A missing rate must read as "no tax shown", never as a crash on a live bill. */
    @Test
    void nullsAreTreatedAsNoTaxRatherThanFailing() {
        assertThat(Invoice.taxWithin(null, new BigDecimal("5"))).isEqualByComparingTo("0.00");
        assertThat(Invoice.taxWithin(new BigDecimal("100.00"), null)).isEqualByComparingTo("0.00");
        assertThat(Invoice.valueWithin(null, new BigDecimal("5"))).isEqualByComparingTo("0.00");
    }

    @Test
    void aNegativeRateIsRefusedRatherThanProducingNegativeTax() {
        assertThat(Invoice.taxWithin(new BigDecimal("100.00"), new BigDecimal("-5")))
                .isEqualByComparingTo("0.00");
    }

    /** Rounding lands on the paisa and must not drift on an awkward number. */
    @Test
    void taxRoundsToPaiseHalfUp() {
        // 37.50 at 5% inclusive: 37.50 × 5 / 105 = 1.7857…
        assertThat(Invoice.taxWithin(new BigDecimal("37.50"), new BigDecimal("5")))
                .isEqualByComparingTo("1.79");
    }

    // ---- a discounted bill --------------------------------------------------

    /**
     * The tax follows what was actually received. ₹200 of food less a ₹50 discount is a
     * ₹150 supply, and at 5% inclusive that contains ₹7.14 of tax — not the ₹9.52 the
     * undiscounted price would contain.
     */
    @Test
    void taxIsWorkedOutOnWhatWasPaidNotOnThePreDiscountPrice() {
        assertThat(Invoice.taxWithin(new BigDecimal("150.00"), new BigDecimal("5")))
                .isEqualByComparingTo("7.14");
        assertThat(Invoice.taxWithin(new BigDecimal("200.00"), new BigDecimal("5")))
                .isEqualByComparingTo("9.52");
    }

    @Test
    void aBillOnlyShowsADiscountLineWhenThereIsOne() {
        assertThat(withDiscount("0.00").hasDiscount()).isFalse();
        assertThat(withDiscount("25.00").hasDiscount()).isTrue();
    }

    private static Invoice withDiscount(String discount) {
        return new Invoice("Canteen", "GSTIN", List.of(),
                new BigDecimal("100.00"), "SAVE25", new BigDecimal(discount),
                new BigDecimal("95.24"),
                new BigDecimal("2.38"), new BigDecimal("2.38"), new BigDecimal("5"),
                true, "note", "Platform fee", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("100.00"), "footer");
    }

    // ---- the struck-through free fee ----------------------------------------

    private static Invoice withFee(String charged, String struck) {
        return new Invoice("Canteen", "GSTIN", List.of(),
                new BigDecimal("100.00"), null, BigDecimal.ZERO, new BigDecimal("95.24"),
                new BigDecimal("2.38"), new BigDecimal("2.38"), new BigDecimal("5"),
                true, "note", "Platform fee",
                new BigDecimal(charged), new BigDecimal(struck),
                BigDecimal.ZERO, new BigDecimal("100.00"), "footer");
    }

    @Test
    void aPriceShownButNotChargedReadsAsFree() {
        assertThat(withFee("0.00", "5.00").feeIsFree()).isTrue();
    }

    @Test
    void aFeeActuallyChargedIsNotFree() {
        assertThat(withFee("5.00", "5.00").feeIsFree()).isFalse();
    }

    /** Nothing configured means no fee line at all, not a strikethrough of zero. */
    @Test
    void noConfiguredFeeIsNotAStrikethrough() {
        assertThat(withFee("0.00", "0.00").feeIsFree()).isFalse();
    }
}
