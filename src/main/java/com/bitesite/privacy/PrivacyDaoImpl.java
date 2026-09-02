package com.bitesite.privacy;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class PrivacyDaoImpl implements PrivacyDao {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void grantConsent(Long userId, ConsentPurpose purpose, String policyVersion) {
        // Only insert when there is no live consent for this purpose at this version.
        // Re-ticking a box the student already agreed to should not add a row.
        Integer live = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consents WHERE user_id = ? AND purpose = ? "
                        + "AND policy_version = ? AND withdrawn_at IS NULL",
                Integer.class, userId, purpose.name(), policyVersion);
        if (live != null && live > 0) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO consents (user_id, purpose, policy_version) VALUES (?, ?, ?)",
                userId, purpose.name(), policyVersion);
    }

    @Override
    public void withdrawConsent(Long userId, ConsentPurpose purpose) {
        jdbcTemplate.update(
                "UPDATE consents SET withdrawn_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = ? AND purpose = ? AND withdrawn_at IS NULL",
                userId, purpose.name());
    }

    @Override
    public List<Consent> findConsents(Long userId) {
        return jdbcTemplate.query(
                "SELECT purpose, policy_version, granted_at, withdrawn_at FROM consents "
                        + "WHERE user_id = ? ORDER BY granted_at DESC",
                (rs, n) -> new Consent(
                        ConsentPurpose.valueOf(rs.getString("purpose")),
                        rs.getString("policy_version"),
                        rs.getObject("granted_at", LocalDateTime.class),
                        rs.getObject("withdrawn_at", LocalDateTime.class)),
                userId);
    }

    @Override
    public void saveRequest(DataRequest request) {
        jdbcTemplate.update(
                "INSERT INTO data_requests (user_id, tenant_id, kind, status, note) VALUES (?, ?, ?, ?, ?)",
                request.getUserId(), request.getTenantId(), request.getKind().name(),
                request.getStatus().name(), request.getNote());
    }

    @Override
    public List<DataRequest> findRequests(DataRequest.Status status, int limit) {
        // Joined for the name and email: a queue row that is only two ids cannot be acted on.
        StringBuilder sql = new StringBuilder(
                "SELECT dr.*, u.name AS user_name, u.email AS user_email FROM data_requests dr "
                        + "JOIN users u ON u.id = dr.user_id");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" WHERE dr.status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY dr.created_at DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, n) -> DataRequest.builder()
                .id(rs.getLong("id"))
                .userId(rs.getLong("user_id"))
                .tenantId(rs.getObject("tenant_id", Long.class))
                .kind(DataRequest.Kind.valueOf(rs.getString("kind")))
                .status(DataRequest.Status.valueOf(rs.getString("status")))
                .note(rs.getString("note"))
                .createdAt(rs.getObject("created_at", LocalDateTime.class))
                .resolvedAt(rs.getObject("resolved_at", LocalDateTime.class))
                .userName(rs.getString("user_name"))
                .userEmail(rs.getString("user_email"))
                .build(), args.toArray());
    }

    @Override
    public void updateRequestStatus(Long id, DataRequest.Status status) {
        boolean finished = status == DataRequest.Status.RESOLVED || status == DataRequest.Status.REJECTED;
        jdbcTemplate.update(
                "UPDATE data_requests SET status = ?, resolved_at = " + (finished ? "CURRENT_TIMESTAMP" : "NULL")
                        + " WHERE id = ?",
                status.name(), id);
    }
}
