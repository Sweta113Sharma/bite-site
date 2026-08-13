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
            .subject(rs.getString("subject"))
            .message(rs.getString("message"))
            .status(GrievanceStatus.valueOf(rs.getString("status")))
            .adminResponse(rs.getString("admin_response"))
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .resolvedAt(rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toLocalDateTime())
            .build();

    @Override
    public Grievance save(Grievance grievance) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO grievances (tenant_id, raised_by_user_id, subject, message, status) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, grievance.getTenantId());
            ps.setLong(2, grievance.getRaisedByUserId());
            ps.setString(3, grievance.getSubject());
            ps.setString(4, grievance.getMessage());
            ps.setString(5, grievance.getStatus().name());
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
                "SELECT g.*, t.name AS college_name, u.name AS raised_by_name, u.email AS raised_by_email "
                        + "FROM grievances g "
                        + "JOIN tenants t ON t.id = g.tenant_id "
                        + "JOIN users u ON u.id = g.raised_by_user_id "
                        + "ORDER BY g.created_at DESC",
                (rs, rowNum) -> new GrievanceAdminView(
                        ROW_MAPPER.mapRow(rs, rowNum),
                        rs.getString("college_name"),
                        rs.getString("raised_by_name"),
                        rs.getString("raised_by_email")));
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
