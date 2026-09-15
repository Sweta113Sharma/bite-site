package com.bitesite.service;

import com.bitesite.dto.GatewayOrder;
import com.bitesite.dto.GatewayRefund;

import java.math.BigDecimal;
import java.util.List;

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

    /** Issues a full refund for a captured payment. Throws
     * {@link com.bitesite.exception.PaymentGatewayException} if the refund can't be
     * completed — callers must not mark anything cancelled/refunded on our side unless
     * this returns normally. {@link com.bitesite.exception.RefundNotSentException} (an
     * amount under the gateway's floor) means certainly nothing was sent. */
    void refund(String gatewayPaymentId, BigDecimal amountRupees);

    /**
     * Refunds part of a captured payment and returns the refund the gateway created, whose
     * id is how its later {@code refund.processed} webhook is matched back to the lines it
     * paid for. Same failure contract as {@link #refund}: an exception means the outcome is
     * unknown, not that no money moved. The one exception is
     * {@link com.bitesite.exception.RefundNotSentException}, thrown before any request is
     * made (an amount under the gateway's floor): that refund certainly did not happen.
     *
     * <p>The default exists for gateway stand-ins in tests that predate partial refunds; it
     * reports no id, which the caller treats as "matched later by amount".
     */
    default GatewayRefund refundPart(String gatewayPaymentId, BigDecimal amountRupees) {
        refund(gatewayPaymentId, amountRupees);
        return new GatewayRefund(null, amountRupees, null);
    }

    /**
     * Every refund the gateway holds against a payment, in any state. This is how a refund
     * whose outcome we never learned gets settled: {@link #refund} can succeed while
     * reporting failure, and the only way to know is to ask. Empty means the gateway has
     * never been asked, or the request never reached it. */
    List<GatewayRefund> refundsFor(String gatewayPaymentId);
}
