package com.bitesite.dao;

import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentDaoImpl implements PaymentDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Payment> ROW_MAPPER = (rs, rowNum) -> Payment.builder()
            .id(rs.getLong("id"))
            .tenantId(rs.getLong("tenant_id"))
            .orderId(rs.getLong("order_id"))
            .razorpayOrderId(rs.getString("razorpay_order_id"))
            .razorpayPaymentId(rs.getString("razorpay_payment_id"))
            .razorpaySignature(rs.getString("razorpay_signature"))
            .amount(rs.getBigDecimal("amount"))
            .refundedAmount(rs.getBigDecimal("refunded_amount"))
            .status(PaymentStatus.valueOf(rs.getString("status")))
            .needsReconciliation(rs.getBoolean("needs_reconciliation"))
            .reconciliationReason(rs.getString("reconciliation_reason"))
            .refundAttemptedAt(rs.getObject("refund_attempted_at", LocalDateTime.class))
            .refundRequestedBy(rs.getObject("refund_requested_by", Long.class))
            .refundReason(rs.getString("refund_reason"))
            .refundAttempts(rs.getInt("refund_attempts"))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .verifiedAt(rs.getObject("verified_at", LocalDateTime.class))
            .build();

    @Override
    public Payment save(Payment payment) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            // razorpay_payment_id is written even though the live callers never have one
            // yet: at checkout the student has not paid, so it is markVerified that fills
            // it in later. Leaving it out of the INSERT meant a caller could set it on the
            // object, watch the save succeed, and get a row without it — no error, no
            // warning. Test fixtures did exactly that, and the rows they left behind were
            // the reason the reconciliation sweep had to learn to skip payments with no
            // gateway reference. A builder field that the database silently discards is a
            // trap whoever meets it next has to debug from the data.
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO payments (tenant_id, order_id, razorpay_order_id, razorpay_payment_id, "
                            + "amount, status) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, payment.getTenantId());
            ps.setLong(2, payment.getOrderId());
            ps.setString(3, payment.getRazorpayOrderId());
            // Nullable, and uq_payments_razorpay_payment permits any number of NULLs in
            // MySQL, so the ordinary "not paid yet" case still inserts cleanly.
            ps.setString(4, payment.getRazorpayPaymentId());
            ps.setBigDecimal(5, payment.getAmount());
            ps.setString(6, payment.getStatus().name());
            return ps;
        }, keyHolder);
        payment.setId(keyHolder.getKey().longValue());
        return payment;
    }

    @Override
    public Optional<Payment> findByOrderId(Long orderId, Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM payments WHERE order_id = ? AND tenant_id = ? ORDER BY created_at DESC LIMIT 1",
                ROW_MAPPER, orderId, tenantId).stream().findFirst();
    }

    @Override
    public Optional<Payment> findByRazorpayOrderId(String razorpayOrderId) {
        return jdbcTemplate.query(
                "SELECT * FROM payments WHERE razorpay_order_id = ?", ROW_MAPPER, razorpayOrderId)
                .stream().findFirst();
    }

    @Override
    public Optional<Payment> findByRazorpayPaymentId(String razorpayPaymentId) {
        // uq_payments_razorpay_payment makes this an index lookup.
        return jdbcTemplate.query(
                "SELECT * FROM payments WHERE razorpay_payment_id = ?", ROW_MAPPER, razorpayPaymentId)
                .stream().findFirst();
    }

    @Override
    public Optional<Payment> findByAnyGatewayReference(String reference) {
        return jdbcTemplate.query(
                "SELECT * FROM payments WHERE razorpay_order_id = ? OR razorpay_payment_id = ? LIMIT 1",
                ROW_MAPPER, reference, reference)
                .stream().findFirst();
    }

    @Override
    public List<Payment> findRecentAcrossTenants(PaymentStatus status, int limit, int offset) {
        // idx_payments_status_created / idx_payments_created (V19) keep both shapes off a
        // full scan.
        if (status == null) {
            return jdbcTemplate.query(
                    "SELECT * FROM payments ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                    ROW_MAPPER, limit, offset);
        }
        return jdbcTemplate.query(
                "SELECT * FROM payments WHERE status = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, status.name(), limit, offset);
    }

    @Override
    public void markVerified(Long id, String razorpayPaymentId, String razorpaySignature, PaymentStatus status) {
        jdbcTemplate.update(
                "UPDATE payments SET razorpay_payment_id = ?, razorpay_signature = ?, status = ?, "
                        + "verified_at = CURRENT_TIMESTAMP WHERE id = ?",
                razorpayPaymentId, razorpaySignature, status.name(), id);
    }

    /** Every status a payment can be in before money has been captured against it. */
    private static final String NOT_YET_CAPTURED = "('CREATED','AUTHORIZED','FAILED')";

    @Override
    public boolean markCaptured(Long id, String razorpayPaymentId, String razorpaySignature) {
        // The status condition is the whole point. A confirmation can arrive again long after
        // the first: the student re-posting the ids and signature their browser was handed,
        // or Razorpay redelivering payment.captured. Unconditionally, either one turned a
        // REFUNDED or REFUND_PENDING payment back into CAPTURED, which made it refundable a
        // second time and dropped a pending refund out of the reconciliation sweep.
        return jdbcTemplate.update(
                "UPDATE payments SET razorpay_payment_id = ?, razorpay_signature = ?, status = 'CAPTURED', "
                        + "verified_at = CURRENT_TIMESTAMP WHERE id = ? AND status IN " + NOT_YET_CAPTURED,
                razorpayPaymentId, razorpaySignature, id) == 1;
    }

    @Override
    public boolean markSignatureRejected(Long id) {
        // FAILED from CAPTURED, REFUND_PENDING or REFUNDED would erase money that did move.
        return jdbcTemplate.update(
                "UPDATE payments SET status = 'FAILED' WHERE id = ? AND status IN ('CREATED','AUTHORIZED')",
                id) == 1;
    }

    @Override
    public void flagForReconciliation(Long id, String reason) {
        jdbcTemplate.update(
                "UPDATE payments SET needs_reconciliation = TRUE, reconciliation_reason = ? WHERE id = ?",
                reason, id);
    }

    @Override
    public void clearReconciliation(Long id) {
        jdbcTemplate.update(
                "UPDATE payments SET needs_reconciliation = FALSE, reconciliation_reason = NULL WHERE id = ?",
                id);
    }

    @Override
    public List<Payment> findNeedingReconciliation(int limit, int offset) {
        return jdbcTemplate.query(
                "SELECT * FROM payments WHERE needs_reconciliation = TRUE "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, limit, offset);
    }

    @Override
    public long countNeedingReconciliation() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE needs_reconciliation = TRUE", Long.class);
        return value == null ? 0L : value;
    }


    @Override
    public boolean claimForRefund(Long id, String cancellationReason, Long requestedBy) {
        // CAPTURED in the WHERE clause is the whole mechanism: the first caller flips the
        // row and every later one matches nothing. Inside cancelOrder's transaction this
        // also takes a row lock, so a second cancel waits for the first to finish rather
        // than racing it, and then finds the payment already refunded.
        return jdbcTemplate.update(
                "UPDATE payments SET status = ?, refund_reason = ?, refund_requested_by = ?, "
                        + "refund_attempted_at = CURRENT_TIMESTAMP, refund_attempts = 1 "
                        + "WHERE id = ? AND status = ?",
                PaymentStatus.REFUND_PENDING.name(), cancellationReason, requestedBy,
                id, PaymentStatus.CAPTURED.name()) == 1;
    }

    @Override
    public boolean claimRefundRetry(Long id, int olderThanMinutes, int maxAttempts) {
        // Same shape as the claim above: the age check, the cap and the bump are one
        // statement, so the database picks the single caller that gets to send.
        return jdbcTemplate.update(
                "UPDATE payments SET refund_attempts = refund_attempts + 1, "
                        + "refund_attempted_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = ? AND refund_attempts < ? "
                        + "AND refund_attempted_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? MINUTE)",
                id, PaymentStatus.REFUND_PENDING.name(), maxAttempts, olderThanMinutes) == 1;
    }

    @Override
    public boolean transitionStatus(Long id, PaymentStatus from, PaymentStatus to) {
        return jdbcTemplate.update(
                "UPDATE payments SET status = ? WHERE id = ? AND status = ?",
                to.name(), id, from.name()) == 1;
    }

    @Override
    public List<Payment> findRefundPendingOlderThan(int minutes) {
        // idx_payments_status_created (V19) narrows this to the handful of pending rows.
        return jdbcTemplate.query(
                "SELECT * FROM payments WHERE status = ? "
                        + "AND refund_attempted_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? MINUTE) "
                        + "ORDER BY refund_attempted_at, id",
                ROW_MAPPER, PaymentStatus.REFUND_PENDING.name(), minutes);
    }

    @Override
    public void updateStatus(Long id, PaymentStatus status) {
        jdbcTemplate.update("UPDATE payments SET status = ? WHERE id = ?", status.name(), id);
    }

    @Override
    public Optional<Payment> lockByOrderId(Long orderId, Long tenantId) {
        // FOR UPDATE is the point: a partial refund claim holds this row until it commits,
        // so a full cancellation's claimForRefund (an UPDATE on the same row) waits behind
        // it and then sees the reduced refundable amount, and vice versa. Only meaningful
        // inside a transaction; RefundLedger is the caller.
        return jdbcTemplate.query(
                "SELECT * FROM payments WHERE order_id = ? AND tenant_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE",
                ROW_MAPPER, orderId, tenantId).stream().findFirst();
    }

    @Override
    public void reservePartialRefund(Long id, java.math.BigDecimal amount) {
        // chk_payments_refunded_amount refuses a total above the capture, so a bug in the
        // arithmetic upstream fails here instead of promising money that was never taken.
        jdbcTemplate.update(
                "UPDATE payments SET refunded_amount = refunded_amount + ? WHERE id = ?", amount, id);
    }

    @Override
    public void releasePartialRefund(Long id, java.math.BigDecimal amount) {
        jdbcTemplate.update(
                "UPDATE payments SET refunded_amount = GREATEST(0, refunded_amount - ?) WHERE id = ?",
                amount, id);
    }
}
