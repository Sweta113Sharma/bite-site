package com.bitesite.service;

import com.bitesite.dto.GatewayOrder;

import java.math.BigDecimal;

/**
 * Seam around the payment provider. {@link RazorpayPaymentGateway} is the only
 * implementation today; a "pay at counter" implementation is deliberately NOT provided —
 * the kitchen must never start prepping an order that isn't paid for yet.
 */
public interface PaymentGateway {

    GatewayOrder createOrder(BigDecimal amountRupees, String receipt);

    /** Verifies the signature the client-side checkout callback hands back. */
    boolean verifyPaymentSignature(String gatewayOrderId, String gatewayPaymentId, String signature);

    /** Verifies the {@code X-Razorpay-Signature} header on an inbound webhook call. */
    boolean verifyWebhookSignature(String payload, String signatureHeader);
}
