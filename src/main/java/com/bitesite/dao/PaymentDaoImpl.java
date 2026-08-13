package com.bitesite.dao;

import com.bitesite.model.Payment;
import com.bitesite.model.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
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
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .verifiedAt(rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toLocalDateTime())
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
