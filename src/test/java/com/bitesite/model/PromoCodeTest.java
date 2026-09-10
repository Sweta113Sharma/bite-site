package com.bitesite.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** What a code is worth, and when it is allowed to be worth anything. */
class PromoCodeTest {

    private static PromoCode.PromoCodeBuilder code() {
        return PromoCode.builder()
                .code("SAVE")
                .discountType(PromoCode.Type.FLAT)
                .discountValue(new BigDecimal("20"))
                .fundedBy(PromoCode.Funder.PLATFORM)
                .active(true);
    }

    // ---- what it is worth ---------------------------------------------------

    @Test
    void aFlatCodeTakesItsFaceValue() {
        assertThat(code().build().discountOn(new BigDecimal("100.00")))
                .isEqualByComparingTo("20.00");
    }

    @Test
    void aPercentCodeTakesAShareOfTheFood() {
        assertThat(code().discountType(PromoCode.Type.PERCENT).discountValue(new BigDecimal("15")).build()
                .discountOn(new BigDecimal("200.00")))
                .isEqualByComparingTo("30.00");
    }

    /** A percentage without a ceiling is an open cheque on a large order. */
    @Test
    void aCeilingCapsAPercentageOnABigOrder() {
        PromoCode c = code().discountType(PromoCode.Type.PERCENT)
                .discountValue(new BigDecimal("50"))
                .maxDiscount(new BigDecimal("40"))
                .build();

        assertThat(c.discountOn(new BigDecimal("1000.00"))).isEqualByComparingTo("40.00");
        // Below the cap it still behaves as a plain percentage.
        assertThat(c.discountOn(new BigDecimal("60.00"))).isEqualByComparingTo("30.00");
    }

    /**
     * The one that turns a promotion into a payout if missed: a ₹100 code on a ₹40 order
     * is a ₹40 discount, not ₹60 handed back.
     */
    @Test
    void aDiscountNeverExceedsTheOrderItself() {
        assertThat(code().discountValue(new BigDecimal("100")).build()
                .discountOn(new BigDecimal("40.00")))
                .isEqualByComparingTo("40.00");
    }

    @Test
    void anEmptyOrderIsWorthNoDiscount() {
        assertThat(code().build().discountOn(BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(code().build().discountOn(null)).isEqualByComparingTo("0");
    }

    // ---- when it applies ----------------------------------------------------

    @Test
    void anUnscopedCodeWorksAnywhere() {
        assertThat(code().build().appliesTo(1L, 10L)).isTrue();
    }

    @Test
    void aCollegeScopedCodeIsRefusedElsewhere() {
        PromoCode c = code().tenantId(1L).build();
        assertThat(c.appliesTo(1L, 10L)).isTrue();
        assertThat(c.appliesTo(2L, 20L)).isFalse();
    }

    @Test
    void aCanteenScopedCodeIsRefusedAtAnotherCanteenInTheSameCollege() {
        PromoCode c = code().tenantId(1L).outletId(10L).build();
        assertThat(c.appliesTo(1L, 10L)).isTrue();
        assertThat(c.appliesTo(1L, 11L)).isFalse();
    }

    // ---- when it is live ----------------------------------------------------

    @Test
    void aWindowIsInclusiveAtBothEnds() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 10, 12, 0);
        PromoCode c = code().validFrom(now.minusDays(1)).validUntil(now.plusDays(1)).build();

        assertThat(c.withinWindow(now)).isTrue();
        assertThat(c.withinWindow(now.minusDays(2))).isFalse();
        assertThat(c.withinWindow(now.plusDays(2))).isFalse();
    }

    @Test
    void noWindowMeansAlwaysLive() {
        assertThat(code().build().withinWindow(LocalDateTime.now())).isTrue();
    }

    @Test
    void aMinimumOrderValueIsInclusive() {
        PromoCode c = code().minOrderValue(new BigDecimal("100")).build();

        assertThat(c.meetsMinimum(new BigDecimal("100.00"))).isTrue();
        assertThat(c.meetsMinimum(new BigDecimal("99.99"))).isFalse();
        assertThat(code().build().meetsMinimum(new BigDecimal("1.00"))).isTrue();
    }
}
