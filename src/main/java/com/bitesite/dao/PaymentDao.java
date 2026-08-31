package com.bitesite.dao;

import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;

import java.util.List;
import java.util.Optional;

public interface PaymentDao {
    Payment save(Payment payment);

    Optional<Payment> findByOrderId(Long orderId, Long tenantId);

    Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

    /**
     * Support-desk lookup by whichever Razorpay reference the student actually has:
     * the order id (order_...) from checkout, or the payment id (pay_...) off their
     * bank statement. Not tenant-scoped — gate on the admin role.
     */
    Optional<Payment> findByAnyGatewayReference(String reference);

    /**
     * Recent payments across every tenant, newest first, optionally narrowed by status.
     * Admin reconciliation only — gate on the admin role, as with the finder above.
     */
    List<Payment> findRecentAcrossTenants(PaymentStatus status, int limit);

    void markVerified(Long id, String razorpayPaymentId, String razorpaySignature, PaymentStatus status);

    void updateStatus(Long id, PaymentStatus status);
}
