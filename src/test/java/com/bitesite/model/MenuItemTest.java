package com.bitesite.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MenuItemTest {

    private MenuItem itemWith(BigDecimal price, BigDecimal discountPrice, BigDecimal discountPercent) {
        return MenuItem.builder()
                .id(1L).tenantId(1L).outletId(1L)
                .name("Samosa").category("Snacks")
                .price(price).discountPrice(discountPrice).discountPercent(discountPercent)
                .available(true)
                .build();
    }

    @Test
    void noDiscountMeansEffectivePriceEqualsPrice() {
        MenuItem item = itemWith(new BigDecimal("30.00"), null, null);
        assertThat(item.effectivePrice()).isEqualByComparingTo("30.00");
        assertThat(item.hasDiscount()).isFalse();
    }

    @Test
    void flatDiscountPriceWinsOverPercent() {
        MenuItem item = itemWith(new BigDecimal("30.00"), new BigDecimal("22.00"), new BigDecimal("50.00"));
        assertThat(item.effectivePrice()).isEqualByComparingTo("22.00");
        assertThat(item.hasDiscount()).isTrue();
    }

    @Test
    void percentDiscountAppliesWhenNoFlatPriceSet() {
        MenuItem item = itemWith(new BigDecimal("30.00"), null, new BigDecimal("10.00"));
        assertThat(item.effectivePrice()).isEqualByComparingTo("27.00");
        assertThat(item.hasDiscount()).isTrue();
    }

    @Test
    void zeroPercentDiscountIsTreatedAsNoDiscount() {
        MenuItem item = itemWith(new BigDecimal("30.00"), null, BigDecimal.ZERO);
        assertThat(item.effectivePrice()).isEqualByComparingTo("30.00");
        assertThat(item.hasDiscount()).isFalse();
    }
}
