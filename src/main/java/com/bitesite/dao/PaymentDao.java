package com.bitesite.dao;

import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;

import java.util.Optional;

public interface PaymentDao {
    Payment save(Payment payment);

    Optional<Payment> findByOrderId(Long orderId, Long tenantId);

    Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

    void markVerified(Long id, String razorpayPaymentId, String razorpaySignature, PaymentStatus status);

    void updateStatus(Long id, PaymentStatus status);
}
