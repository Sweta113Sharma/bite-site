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
            .status(PaymentStatus.valueOf(rs.getString("status")))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .verifiedAt(rs.getObject("verified_at", LocalDateTime.class))
            .build();

    @Override
    public Payment save(Payment payment) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO payments (tenant_id, order_id, razorpay_order_id, amount, status) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, payment.getTenantId());
            ps.setLong(2, payment.getOrderId());
            ps.setString(3, payment.getRazorpayOrderId());
            ps.setBigDecimal(4, payment.getAmount());
            ps.setString(5, payment.getStatus().name());
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
                    "SELECT * FROM payments ORDER BY created_at DESC LIMIT ? OFFSET ?",
                    ROW_MAPPER, limit, offset);
        }
        return jdbcTemplate.query(
                "SELECT * FROM payments WHERE status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, status.name(), limit, offset);
    }

    @Override
    public void markVerified(Long id, String razorpayPaymentId, String razorpaySignature, PaymentStatus status) {
        jdbcTemplate.update(
                "UPDATE payments SET razorpay_payment_id = ?, razorpay_signature = ?, status = ?, "
                        + "verified_at = CURRENT_TIMESTAMP WHERE id = ?",
                razorpayPaymentId, razorpaySignature, status.name(), id);
    }

    @Override
    public void updateStatus(Long id, PaymentStatus status) {
        jdbcTemplate.update("UPDATE payments SET status = ? WHERE id = ?", status.name(), id);
    }
}
