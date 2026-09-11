package com.bitesite.dao;

import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code save} actually stores, as opposed to what it is handed.
 *
 * <p>The INSERT named five columns while {@link Payment} carries more, so a field set on
 * the object and absent from the statement was dropped without a word: the save returned
 * normally, the row came back with a null, and nothing anywhere said why. That is not a
 * failure any mock can catch, because a mocked DAO stores whatever it is given.
 *
 * <p>It was `razorpay_payment_id` that went missing, which mattered more than it looks.
 * Live callers genuinely have no payment id at save time, so production rows were correct
 * either way and the gap stayed invisible there; test fixtures set one and lost it, leaving
 * REFUND_PENDING rows with no gateway reference — a state the reconciliation sweep then had
 * to be taught to survive.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentRoundTripTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentDao paymentDao;

    private Payment saved(String gatewayPaymentId) {
        String runId = UUID.randomUUID().toString().substring(0, 12);
        Long outletId = jdbc.queryForObject("SELECT id FROM outlets ORDER BY id LIMIT 1", Long.class);
        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM outlets WHERE id = ?", Long.class, outletId);
        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE tenant_id = ? ORDER BY id LIMIT 1", Long.class, tenantId);
        jdbc.update("INSERT INTO orders (tenant_id, outlet_id, user_id, token_no, total_amount, status) "
                + "VALUES (?, ?, ?, ?, ?, 'PAID')", tenantId, outletId, userId, "PRT-" + runId, new BigDecimal("42.00"));
        Long orderId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Payment payment = paymentDao.save(Payment.builder()
                .tenantId(tenantId).orderId(orderId)
                .razorpayOrderId("rp_order_" + runId)
                .razorpayPaymentId(gatewayPaymentId)
                .amount(new BigDecimal("42.00"))
                .status(PaymentStatus.CAPTURED)
                .build());
        return paymentDao.findByOrderId(orderId, tenantId).orElseThrow();
    }

    @Test
    void aPaymentIdSetBeforeSavingSurvivesTheRoundTrip() {
        String gatewayPaymentId = "pay_" + UUID.randomUUID().toString().substring(0, 14);

        assertThat(saved(gatewayPaymentId).getRazorpayPaymentId())
                .as("a field set on the object and dropped by the INSERT fails silently, "
                        + "which is the one way a persistence bug reaches production unnoticed")
                .isEqualTo(gatewayPaymentId);
    }

    /** The ordinary path: at checkout nobody has paid yet, so there is no payment id to
     * store and the unique index has to tolerate every unpaid row holding a NULL. */
    @Test
    void aPaymentWithNoGatewayPaymentIdStillSaves() {
        assertThat(saved(null).getRazorpayPaymentId()).isNull();
        assertThat(saved(null).getRazorpayPaymentId()).isNull();
    }
}
