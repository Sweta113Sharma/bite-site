package com.bitesite.dao;

import com.bitesite.model.OrderRefund;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OrderRefundDaoImpl implements OrderRefundDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<OrderRefund> ROW_MAPPER = (rs, rowNum) -> OrderRefund.builder()
            .id(rs.getLong("id"))
            .tenantId(rs.getLong("tenant_id"))
            .orderId(rs.getLong("order_id"))
            .paymentId(rs.getLong("payment_id"))
            .amount(rs.getBigDecimal("amount"))
            .status(OrderRefund.Status.valueOf(rs.getString("status")))
            .reason(rs.getString("reason"))
            .gatewayRefundId(rs.getString("gateway_refund_id"))
            .requestedBy(rs.getObject("requested_by", Long.class))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .settledAt(rs.getObject("settled_at", LocalDateTime.class))
            .build();

    @Override
    public OrderRefund insertPending(OrderRefund refund) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO order_refunds (tenant_id, order_id, payment_id, amount, status, reason, requested_by) "
                            + "VALUES (?, ?, ?, ?, 'PENDING', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, refund.getTenantId());
            ps.setLong(2, refund.getOrderId());
            ps.setLong(3, refund.getPaymentId());
            ps.setBigDecimal(4, refund.getAmount());
            ps.setString(5, refund.getReason());
            ps.setObject(6, refund.getRequestedBy());
            return ps;
        }, keyHolder);
        refund.setId(keyHolder.getKey().longValue());
        refund.setStatus(OrderRefund.Status.PENDING);
        return refund;
    }

    @Override
    public List<OrderRefund> findByOrderId(Long orderId, Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM order_refunds WHERE order_id = ? AND tenant_id = ? ORDER BY id",
                ROW_MAPPER, orderId, tenantId);
    }

    @Override
    public Optional<OrderRefund> findByGatewayRefundId(String gatewayRefundId) {
        if (gatewayRefundId == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT * FROM order_refunds WHERE gateway_refund_id = ?", ROW_MAPPER, gatewayRefundId)
                .stream().findFirst();
    }

    @Override
    public Optional<OrderRefund> findUnmatchedPending(Long paymentId, BigDecimal amount) {
        return jdbcTemplate.query(
                "SELECT * FROM order_refunds WHERE payment_id = ? AND amount = ? AND status = 'PENDING' "
                        + "AND gateway_refund_id IS NULL ORDER BY id LIMIT 1",
                ROW_MAPPER, paymentId, amount).stream().findFirst();
    }

    @Override
    public List<OrderRefund> findPendingOlderThan(int minutes) {
        // Same shape as PaymentDao.findRefundPendingOlderThan: old enough that the HTTP call
        // that started it cannot still be in flight, with the age resolved by the database.
        return jdbcTemplate.query(
                "SELECT * FROM order_refunds WHERE status = 'PENDING' "
                        + "AND created_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? MINUTE) ORDER BY id",
                ROW_MAPPER, minutes);
    }

    @Override
    public List<String> gatewayIdsForPayment(Long paymentId) {
        return jdbcTemplate.queryForList(
                "SELECT gateway_refund_id FROM order_refunds WHERE payment_id = ? AND gateway_refund_id IS NOT NULL",
                String.class, paymentId);
    }

    @Override
    public boolean markRefunded(Long id, String gatewayRefundId) {
        // COALESCE keeps an id we already hold if the caller has none to offer.
        return jdbcTemplate.update(
                "UPDATE order_refunds SET status = 'REFUNDED', settled_at = CURRENT_TIMESTAMP, "
                        + "gateway_refund_id = COALESCE(?, gateway_refund_id) "
                        + "WHERE id = ? AND status = 'PENDING'",
                gatewayRefundId, id) == 1;
    }

    @Override
    public boolean markFailed(Long id, String gatewayRefundId) {
        return jdbcTemplate.update(
                "UPDATE order_refunds SET status = 'FAILED', settled_at = CURRENT_TIMESTAMP, "
                        + "gateway_refund_id = COALESCE(gateway_refund_id, ?) "
                        + "WHERE id = ? AND status <> 'FAILED'",
                gatewayRefundId, id) == 1;
    }

    @Override
    public int countPending(Long paymentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_refunds WHERE payment_id = ? AND status = 'PENDING'",
                Integer.class, paymentId);
        return count == null ? 0 : count;
    }
}
