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

    /** What partial refunds leave behind can be a few paise; nothing may be sent for it,
     * and the caller has to be able to tell that apart from a call that timed out. */
    @Test
    void aFullRefundUnderOneRupeeIsRefusedAsNotSent() {
        assertThatThrownBy(() -> configured().refund("pay_x", new BigDecimal("0.50")))
                .isInstanceOf(com.bitesite.exception.RefundNotSentException.class);
        assertThatThrownBy(() -> configured().refundPart("pay_x", new BigDecimal("0.99")))
                .isInstanceOf(com.bitesite.exception.RefundNotSentException.class);
        assertThatThrownBy(() -> gatewayWith("", "").refund("pay_x", new BigDecimal("1.00")))
                .as("exactly ₹1 passes the floor and fails later, as an ordinary gateway error")
                .isInstanceOf(PaymentGatewayException.class)
                .isNotInstanceOf(com.bitesite.exception.RefundNotSentException.class);
    }

    /**
     * A blank webhook secret is treated as unset and every webhook refused. An empty one
     * already failed inside the SDK ("Empty key"), but a whitespace-only one was used as a
     * real HMAC key, which anyone who guesses it can sign with. Defence in depth: production
     * has the secret set.
     */
    @Test
    void aWebhookIsNeverTrustedWhenNoWebhookSecretIsConfigured() throws Exception {
        String body = "{\"event\":\"payment.captured\"}";
        // HMAC-SHA256 of the body under an empty key and under a key of three spaces,
        // computed outside Java: what an attacker would send.
        String emptyKeySignature = "a19950341d76024638d18b6848a6d0f1ceba66e6d706f5db0bb165d2e55c00a5";
        String spacesKeySignature = "d0b8908d2b6ccb47f5e179a9849c5faa5889c31e6e3c14920af72823309358de";
        org.assertj.core.api.Assertions.assertThat(new RazorpayPaymentGateway(new RazorpayProperties("k", "s", ""))
                .verifyWebhookSignature(body, emptyKeySignature)).isFalse();
        org.assertj.core.api.Assertions.assertThat(new RazorpayPaymentGateway(new RazorpayProperties("k", "s", "   "))
                .verifyWebhookSignature(body, spacesKeySignature)).isFalse();
        org.assertj.core.api.Assertions.assertThat(new RazorpayPaymentGateway(new RazorpayProperties("k", "s", null))
                .verifyWebhookSignature(body, emptyKeySignature)).isFalse();
    }

    @Test
    void refusesToBuildAClientWhenCredentialsAreMissing() {
        assertThatThrownBy(() -> gatewayWith("", "").createOrder(new BigDecimal("50.00"), "BITE-1234"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("not configured");
    }
}
