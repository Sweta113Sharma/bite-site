package com.bitesite.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether payments can actually run, so a half-configured gateway is visible
 * instead of silent.
 *
 * <p>The failure mode this exists for: {@code RazorpayProperties.isConfigured()} only
 * checks the key pair, because that is all {@code createOrder} and {@code refund} need.
 * The webhook secret is separate, and nothing needs it until Razorpay posts to
 * {@code /api/payments/webhook} — at which point signature verification fails, the
 * webhook is rejected with a 400, and the order is never confirmed by the asynchronous
 * path. Checkout still looks fine, because the browser callback confirms most payments
 * on its own. The safety net for a student who closes the tab mid-payment is what
 * quietly stops working.
 *
 * <p>Always reports UP, and says what is wrong in the details instead. Returning DOWN
 * would be more expressive but drags the aggregate {@code /actuator/health} down with
 * it, and that endpoint backs the container readiness probe — an unconfigured gateway
 * would stop the app being routable at all, turning "cannot take payments yet" into
 * "cannot serve the menu either". Config state is an operator's question, not a
 * liveness one.
 */
@Component("payments")
@RequiredArgsConstructor
public class PaymentsHealthIndicator implements HealthIndicator {

    private final RazorpayProperties properties;

    @Override
    public Health health() {
        boolean keys = properties.isConfigured();
        boolean webhook = properties.webhookSecret() != null && !properties.webhookSecret().isBlank();

        return Health.up()
                .withDetail("gateway", "razorpay")
                .withDetail("ready", keys && webhook)
                // Never the values themselves: /actuator/health is reachable by every
                // admin and tech manager, and this would be a fine place to leak a secret.
                .withDetail("apiKeys", keys ? "configured" : "MISSING — payments will fail")
                .withDetail("webhookSecret", webhook ? "configured"
                        : "MISSING — payment.captured webhooks will be rejected")
                .withDetail("mode", modeOf(properties.keyId()))
                .build();
    }

    /** Razorpay key ids are prefixed rzp_test_ / rzp_live_, which is worth surfacing. */
    private String modeOf(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            return "unset";
        }
        if (keyId.startsWith("rzp_live_")) {
            return "LIVE";
        }
        if (keyId.startsWith("rzp_test_")) {
            return "test";
        }
        return "unrecognised key prefix";
    }
}
