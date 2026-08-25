package com.bitesite.service;

import com.bitesite.config.RazorpayProperties;
import com.bitesite.exception.PaymentGatewayException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the two things this gateway decides before any network call happens. Everything
 * past that point is Razorpay's SDK talking to Razorpay, which a unit test cannot assert
 * anything useful about.
 */
class RazorpayPaymentGatewayTest {

    private static RazorpayPaymentGateway gatewayWith(String keyId, String keySecret) {
        return new RazorpayPaymentGateway(new RazorpayProperties(keyId, keySecret, "whsec"));
    }

    private static RazorpayPaymentGateway configured() {
        return gatewayWith("rzp_test_key", "secret");
    }

    @Test
    void refusesAnOrderBelowRazorpaysOneRupeeMinimum() {
        // Reachable through a full-value discount or a sub-rupee item. Razorpay's own
        // error for this is opaque, so it is caught here with something a student can read.
        assertThatThrownBy(() -> configured().createOrder(new BigDecimal("0.99"), "BITE-1234"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("under ₹1");
    }

    @Test
    void refusesAZeroAmountOrder() {
        assertThatThrownBy(() -> configured().createOrder(BigDecimal.ZERO, "BITE-1234"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("under ₹1");
    }

    @Test
    void exactlyOneRupeeIsAllowedThroughToTheGateway() {
        // 100 paise is the boundary and must pass the guard. It then fails reaching the
        // fake credentials, which is a different exception path and proves the guard let it by.
        assertThatThrownBy(() -> configured().createOrder(new BigDecimal("1.00"), "BITE-1234"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageNotContaining("under ₹1");
    }

    @Test
    void refusesToBuildAClientWhenCredentialsAreMissing() {
        assertThatThrownBy(() -> gatewayWith("", "").createOrder(new BigDecimal("50.00"), "BITE-1234"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("not configured");
    }
}
