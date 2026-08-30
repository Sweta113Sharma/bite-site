package com.bitesite.controller.api;

import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.service.OrderService;
import com.bitesite.service.PaymentGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Razorpay's asynchronous source of truth for payment confirmation — the client-side
 * checkout callback ({@code CheckoutController.confirm}) gives a snappier UX, but a
 * closed browser tab or dropped connection must not be able to leave a paid order stuck
 * at AWAITING_PAYMENT. Both paths converge on the same idempotent
 * {@link OrderService#confirmPayment}.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class RazorpayWebhookController {

    private final PaymentGateway paymentGateway;
    private final OrderService orderService;

    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(@RequestBody String payload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signatureHeader) {
        if (signatureHeader == null || !paymentGateway.verifyWebhookSignature(payload, signatureHeader)) {
            log.warn("Rejected Razorpay webhook with missing/invalid signature");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("invalid signature");
        }

        JSONObject body = new JSONObject(payload);
        String event = body.optString("event", "");
        if ("payment.captured".equals(event)) {
            JSONObject entity = body.getJSONObject("payload").getJSONObject("payment").getJSONObject("entity");
            String gatewayPaymentId = entity.getString("id");
            String gatewayOrderId = entity.getString("order_id");
            try {
                orderService.confirmPayment(gatewayOrderId, gatewayPaymentId, null);
            } catch (ResourceNotFoundException e) {
                // A capture for a gateway order we have no local payment row for. Razorpay
                // retries non-2xx responses on a schedule, and this will never succeed on a
                // retry — the row is not going to appear — so answering 200 stops an
                // indefinite retry loop while the log keeps the money visible for manual
                // reconciliation. Anything else that throws still surfaces as a 500 and is
                // retried, which is what we want for a transient fault.
                log.error("Razorpay captured payment {} for gateway order {} with no matching payment row. "
                        + "Acknowledged to stop retries; this needs manual reconciliation.",
                        gatewayPaymentId, gatewayOrderId, e);
            }
        }
        return ResponseEntity.ok("ok");
    }
}
