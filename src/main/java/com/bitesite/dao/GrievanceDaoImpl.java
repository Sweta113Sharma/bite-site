package com.bitesite.dao;

import com.bitesite.dto.GrievanceAdminView;
import com.bitesite.model.Grievance;
import com.bitesite.model.GrievanceStatus;
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
public class GrievanceDaoImpl implements GrievanceDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Grievance> ROW_MAPPER = (rs, rowNum) -> Grievance.builder()
            .id(rs.getLong("id"))
            .tenantId(rs.getLong("tenant_id"))
            .raisedByUserId(rs.getLong("raised_by_user_id"))
            // getLong yields 0 for SQL NULL, which would read as "order 0"; getObject
            // keeps a ticket with no order genuinely null.
            .orderId(rs.getObject("order_id", Long.class))
            .subject(rs.getString("subject"))
            .message(rs.getString("message"))
            .status(GrievanceStatus.valueOf(rs.getString("status")))
            .adminResponse(rs.getString("admin_response"))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .resolvedAt(rs.getObject("resolved_at", LocalDateTime.class))
            .build();

    @Override
    public Grievance save(Grievance grievance) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO grievances (tenant_id, raised_by_user_id, order_id, subject, message, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, grievance.getTenantId());
            ps.setLong(2, grievance.getRaisedByUserId());
            if (grievance.getOrderId() == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, grievance.getOrderId());
            }
            ps.setString(4, grievance.getSubject());
            ps.setString(5, grievance.getMessage());
            ps.setString(6, grievance.getStatus().name());
            return ps;
        }, keyHolder);
        grievance.setId(keyHolder.getKey().longValue());
        return grievance;
    }

    @Override
    public Optional<Grievance> findByIdAndTenantId(Long id, Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM grievances WHERE id = ? AND tenant_id = ?", ROW_MAPPER, id, tenantId)
                .stream().findFirst();
    }

    @Override
    public Optional<Grievance> findById(Long id) {
        return jdbcTemplate.query("SELECT * FROM grievances WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public List<GrievanceAdminView> findAllWithDetails() {
        return jdbcTemplate.query(
                "SELECT g.*, t.name AS college_name, u.name AS raised_by_name, u.email AS raised_by_email, "
                        + "o.token_no AS order_token "
                        + "FROM grievances g "
                        + "JOIN tenants t ON t.id = g.tenant_id "
                        + "JOIN users u ON u.id = g.raised_by_user_id "
                        + "LEFT JOIN orders o ON o.id = g.order_id "
                        + "ORDER BY g.created_at DESC",
                (rs, rowNum) -> new GrievanceAdminView(
                        ROW_MAPPER.mapRow(rs, rowNum),
                        rs.getString("college_name"),
                        rs.getString("raised_by_name"),
                        rs.getString("raised_by_email"),
                        rs.getString("order_token")));
    }

    @Override
    public List<Grievance> findByTenantId(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM grievances WHERE tenant_id = ? ORDER BY created_at DESC", ROW_MAPPER, tenantId);
    }

    @Override
    public List<Grievance> findByUserIdAndTenantId(Long userId, Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM grievances WHERE raised_by_user_id = ? AND tenant_id = ? ORDER BY created_at DESC",
                ROW_MAPPER, userId, tenantId);
    }

    @Override
    public void resolve(Long id, Long tenantId, String adminResponse, GrievanceStatus status) {
        jdbcTemplate.update(
                "UPDATE grievances SET admin_response = ?, status = ?, resolved_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND tenant_id = ?",
                adminResponse, status.name(), id, tenantId);
    }
}
