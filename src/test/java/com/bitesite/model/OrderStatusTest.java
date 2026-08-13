package com.bitesite.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    void awaitingPaymentCanMoveToPaidFailedExpiredOrCancelled() {
        assertThat(OrderStatus.AWAITING_PAYMENT.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.AWAITING_PAYMENT.canTransitionTo(OrderStatus.PAYMENT_FAILED)).isTrue();
        assertThat(OrderStatus.AWAITING_PAYMENT.canTransitionTo(OrderStatus.EXPIRED)).isTrue();
        assertThat(OrderStatus.AWAITING_PAYMENT.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
    }

    @Test
    void awaitingPaymentCannotSkipStraightToPreparingOrReady() {
        assertThat(OrderStatus.AWAITING_PAYMENT.canTransitionTo(OrderStatus.PREPARING)).isFalse();
        assertThat(OrderStatus.AWAITING_PAYMENT.canTransitionTo(OrderStatus.READY_FOR_PICKUP)).isFalse();
        assertThat(OrderStatus.AWAITING_PAYMENT.canTransitionTo(OrderStatus.COMPLETED)).isFalse();
    }

    @Test
    void kitchenOnlySeesPaidAndLaterStates() {
        assertThat(OrderStatus.AWAITING_PAYMENT.isKitchenVisible()).isFalse();
        assertThat(OrderStatus.PAID.isKitchenVisible()).isTrue();
        assertThat(OrderStatus.PREPARING.isKitchenVisible()).isTrue();
        assertThat(OrderStatus.READY_FOR_PICKUP.isKitchenVisible()).isTrue();
        assertThat(OrderStatus.COMPLETED.isKitchenVisible()).isFalse();
        assertThat(OrderStatus.PAYMENT_FAILED.isKitchenVisible()).isFalse();
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        for (OrderStatus terminal : new OrderStatus[]{OrderStatus.COMPLETED, OrderStatus.EXPIRED, OrderStatus.CANCELLED}) {
            assertThat(terminal.isTerminal()).isTrue();
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(terminal.canTransitionTo(target))
                        .as("%s -> %s should be blocked", terminal, target)
                        .isFalse();
            }
        }
    }

    @Test
    void fullHappyPathIsReachableStepByStep() {
        assertThat(OrderStatus.AWAITING_PAYMENT.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PREPARING)).isTrue();
        assertThat(OrderStatus.PREPARING.canTransitionTo(OrderStatus.READY_FOR_PICKUP)).isTrue();
        assertThat(OrderStatus.READY_FOR_PICKUP.canTransitionTo(OrderStatus.COMPLETED)).isTrue();
    }

    @Test
    void paymentFailedCanBeRetried() {
        assertThat(OrderStatus.PAYMENT_FAILED.canTransitionTo(OrderStatus.AWAITING_PAYMENT)).isTrue();
    }
}
