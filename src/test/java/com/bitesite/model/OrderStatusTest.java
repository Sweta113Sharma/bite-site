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
        for (OrderStatus terminal : new OrderStatus[]{OrderStatus.COMPLETED, OrderStatus.CANCELLED}) {
            assertThat(terminal.isTerminal()).isTrue();
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(terminal.canTransitionTo(target))
                        .as("%s -> %s should be blocked", terminal, target)
                        .isFalse();
            }
        }
    }

    /**
     * EXPIRED is terminal to the student — there is no button anywhere that moves an
     * expired order — but it has exactly one way out, and the exception is deliberate.
     *
     * <p>Unpaid orders expire on a timer that a slow bank OTP can outlast. When the capture
     * finally lands, the choice is to revive the order or to hold money for food nobody is
     * going to make. Reviving is the better answer, so {@code confirmPayment} is allowed to
     * make that one move. Everything else stays shut, including the moves that would let a
     * canteen quietly resurrect an order nobody paid for.
     */
    @Test
    void expiredCanOnlyBeRevivedByAPaymentThatArrivedLate() {
        assertThat(OrderStatus.EXPIRED.isTerminal())
                .as("still terminal as far as the student and the kitchen are concerned")
                .isTrue();
        assertThat(OrderStatus.EXPIRED.canTransitionTo(OrderStatus.PAID)).isTrue();

        for (OrderStatus target : OrderStatus.values()) {
            if (target == OrderStatus.PAID) {
                continue;
            }
            assertThat(OrderStatus.EXPIRED.canTransitionTo(target))
                    .as("EXPIRED -> %s should be blocked", target)
                    .isFalse();
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

    /**
     * The order page draws its progress rail from this, so a status landing on the wrong
     * step, or on the path at all when it should not be, is a wrong answer to "where is my
     * order" rather than a cosmetic slip.
     */
    @Test
    void theHappyPathIsNumberedInOrder() {
        assertThat(OrderStatus.AWAITING_PAYMENT.progressStep()).isZero();
        assertThat(OrderStatus.PAID.progressStep()).isEqualTo(1);
        assertThat(OrderStatus.PREPARING.progressStep()).isEqualTo(2);
        assertThat(OrderStatus.READY_FOR_PICKUP.progressStep()).isEqualTo(3);
        assertThat(OrderStatus.COMPLETED.progressStep()).isEqualTo(4);
    }

    /** An order that stopped is off the path entirely. Drawing it stalled midway would say
     * it is still moving. */
    @Test
    void ordersThatWentWrongAreNotOnTheHappyPath() {
        assertThat(OrderStatus.CANCELLED.progressStep()).isNegative();
        assertThat(OrderStatus.EXPIRED.progressStep()).isNegative();
        assertThat(OrderStatus.PAYMENT_FAILED.progressStep()).isNegative();
    }
}
