package com.bitesite.dto;

import com.bitesite.model.Order;
import com.bitesite.model.OrderStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This mapping is read by two renderers — the Thymeleaf fragment on first paint and the
 * poller on every refresh — so it is the one place a wrong answer shows up as a strip
 * stuck on a state the order left minutes ago.
 */
class ActiveOrderStripViewTest {

    private static Order order(long id, OrderStatus status) {
        return Order.builder().id(id).tokenNo("BITE-000" + id).status(status).build();
    }

    @Test
    void noLiveOrdersMeansNoStrip() {
        assertThat(ActiveOrderStripView.from(List.of())).isNull();
        assertThat(ActiveOrderStripView.from(null)).isNull();
    }

    @Test
    void readyForPickupAsksForTheCode() {
        ActiveOrderStripView strip = ActiveOrderStripView.from(List.of(order(1, OrderStatus.READY_FOR_PICKUP)));

        assertThat(strip.variant()).isEqualTo("is-ready");
        assertThat(strip.label()).isEqualTo("Ready to collect");
        assertThat(strip.cta()).isEqualTo("Show code");
    }

    @Test
    void anUnpaidOrderAsksForPayment() {
        ActiveOrderStripView strip = ActiveOrderStripView.from(List.of(order(1, OrderStatus.AWAITING_PAYMENT)));

        assertThat(strip.variant()).isEqualTo("is-unpaid");
        assertThat(strip.cta()).isEqualTo("Pay now");
    }

    @Test
    void aFailedPaymentSaysSoButStillOffersToPay() {
        ActiveOrderStripView strip = ActiveOrderStripView.from(List.of(order(1, OrderStatus.PAYMENT_FAILED)));

        assertThat(strip.variant()).isEqualTo("is-unpaid");
        assertThat(strip.label()).isEqualTo("Payment failed");
        assertThat(strip.cta()).isEqualTo("Pay now");
    }

    @Test
    void preparingAndPaidAreDistinctStates() {
        assertThat(ActiveOrderStripView.from(List.of(order(1, OrderStatus.PREPARING))).variant())
                .isEqualTo("is-preparing");
        assertThat(ActiveOrderStripView.from(List.of(order(1, OrderStatus.PAID))).variant())
                .isEqualTo("is-paid");
    }

    @Test
    void theStripDescribesTheFirstOrderAndCountsTheRest() {
        ActiveOrderStripView strip = ActiveOrderStripView.from(List.of(
                order(1, OrderStatus.READY_FOR_PICKUP),
                order(2, OrderStatus.PREPARING),
                order(3, OrderStatus.PAID)));

        assertThat(strip.orderId()).isEqualTo(1L);
        assertThat(strip.tokenNo()).isEqualTo("BITE-0001");
        assertThat(strip.moreCount()).isEqualTo(2);
    }

    /** The server stamps this onto the markup and the poller recomputes it from JSON. If
     * the field order here ever stops matching active-order-strip.js, every page load
     * would redraw the strip once for no reason. */
    @Test
    void theSignatureCoversEverythingTheStripDraws() {
        ActiveOrderStripView strip = ActiveOrderStripView.from(List.of(order(7, OrderStatus.PREPARING)));

        assertThat(strip.signature())
                .isEqualTo("7|is-preparing|Being prepared|BITE-0007|0|Track");
    }
}
