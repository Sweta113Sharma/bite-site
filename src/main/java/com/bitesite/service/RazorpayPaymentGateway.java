package com.bitesite.service;

import com.bitesite.config.RazorpayProperties;
import com.bitesite.dto.GatewayOrder;
import com.bitesite.exception.PaymentGatewayException;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Slf4j
public class RazorpayPaymentGateway implements PaymentGateway {

    private static final String CURRENCY = "INR";

    /** Razorpay rejects anything under 1 rupee. Checked here rather than at the call site
     * because it is the gateway's rule, not the app's, and the error it returns otherwise
     * is opaque. Reachable in practice: a 100%% discount, or a sub-rupee item. */
    private static final long MIN_AMOUNT_PAISE = 100;

    private final RazorpayProperties properties;

    /** Rupees to paise, the unit every Razorpay amount field uses. HALF_UP so a price
     * that somehow carries sub-paise precision rounds the way money is expected to. */
    private static long toPaise(BigDecimal amountRupees) {
        return amountRupees.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private RazorpayClient client() {
        if (!properties.isConfigured()) {
            throw new PaymentGatewayException(
                    "Payment is not configured yet — set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET.");
        }
        try {
            return new RazorpayClient(properties.keyId(), properties.keySecret());
        } catch (RazorpayException e) {
            throw new PaymentGatewayException("Could not reach the payment gateway", e);
        }
    }

    @Override
    public GatewayOrder createOrder(BigDecimal amountRupees, String receipt) {
        long amountPaise = toPaise(amountRupees);
        if (amountPaise < MIN_AMOUNT_PAISE) {
            throw new PaymentGatewayException(
                    "Orders under ₹1 can't be paid for online. Please add something else to your order.");
        }
        try {
            JSONObject request = new JSONObject();
            request.put("amount", amountPaise);
            request.put("currency", CURRENCY);
            request.put("receipt", receipt);
            Order order = client().orders.create(request);
            String gatewayOrderId = order.get("id");
            return new GatewayOrder(gatewayOrderId, properties.keyId(), amountPaise, CURRENCY);
        } catch (RazorpayException e) {
            log.error("Razorpay order creation failed for receipt {}", receipt, e);
            throw new PaymentGatewayException("Could not start payment — please try again.", e);
        }
    }

    @Override
    public boolean verifyPaymentSignature(String gatewayOrderId, String gatewayPaymentId, String signature) {
        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", gatewayOrderId);
            options.put("razorpay_payment_id", gatewayPaymentId);
            options.put("razorpay_signature", signature);
            return Utils.verifyPaymentSignature(options, properties.keySecret());
        } catch (RazorpayException e) {
            log.warn("Payment signature verification failed for order {}", gatewayOrderId, e);
            return false;
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signatureHeader) {
        try {
            return Utils.verifyWebhookSignature(payload, signatureHeader, properties.webhookSecret());
        } catch (RazorpayException e) {
            log.warn("Webhook signature verification failed", e);
            return false;
        }
    }

    @Override
    public void refund(String gatewayPaymentId, BigDecimal amountRupees) {
        long amountPaise = toPaise(amountRupees);
        try {
            JSONObject request = new JSONObject();
            request.put("amount", amountPaise);
            client().payments.refund(gatewayPaymentId, request);
        } catch (RazorpayException e) {
            log.error("Razorpay refund failed for payment {}", gatewayPaymentId, e);
            throw new PaymentGatewayException("Could not process the refund — please try again.", e);
        }
    }
}
