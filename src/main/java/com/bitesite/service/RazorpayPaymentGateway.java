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

    private final RazorpayProperties properties;

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
        long amountPaise = amountRupees.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
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
        long amountPaise = amountRupees.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
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
