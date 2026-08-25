package com.bitesite.dao;

import com.bitesite.model.Role;
import com.bitesite.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class UserDaoImpl implements UserDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<User> ROW_MAPPER = (rs, rowNum) -> User.builder()
            .id(rs.getLong("id"))
            .tenantId(rs.getObject("tenant_id", Long.class))
            .outletId(rs.getObject("outlet_id", Long.class))
            .name(rs.getString("name"))
            .email(rs.getString("email"))
            .passwordHash(rs.getString("password_hash"))
            .phone(rs.getString("phone"))
            .rollNo(rs.getString("roll_no"))
            .role(Role.valueOf(rs.getString("role")))
            .activeRole(Role.valueOf(rs.getString("active_role")))
            .active(rs.getBoolean("is_active"))
            .emailVerified(rs.getBoolean("email_verified"))
            .phoneVerified(rs.getBoolean("phone_verified"))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .build();

    @Override
    public Optional<User> findById(Long id) {
        return jdbcTemplate.query("SELECT * FROM users WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst()
                .map(this::loadRoles);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jdbcTemplate.query("SELECT * FROM users WHERE email = ?", ROW_MAPPER, email)
                .stream().findFirst()
                .map(this::loadRoles);
    }

    @Override
    public boolean existsByEmail(String email) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        return count != null && count > 0;
    }

    @Override
    public List<User> findByTenantId(Long tenantId) {
        List<User> users = jdbcTemplate.query(
                "SELECT * FROM users WHERE tenant_id = ? ORDER BY created_at DESC", ROW_MAPPER, tenantId);
        users.forEach(this::loadRoles);
        return users;
    }

    @Override
    public List<User> findPlatformUsers() {
        List<User> users = jdbcTemplate.query(
                "SELECT * FROM users WHERE tenant_id IS NULL ORDER BY created_at DESC", ROW_MAPPER);
        users.forEach(this::loadRoles);
        return users;
    }

    @Override
    public User save(User user) {
        if (user.getId() == null) {
            // Default active_role to primary role if not explicitly set
            Role activeRole = user.getActiveRole() != null ? user.getActiveRole() : user.getRole();

            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO users (tenant_id, outlet_id, name, email, password_hash, phone, roll_no, "
                                + "role, active_role, is_active, email_verified, phone_verified) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setObject(1, user.getTenantId());
                ps.setObject(2, user.getOutletId());
                ps.setString(3, user.getName());
                ps.setString(4, user.getEmail());
                ps.setString(5, user.getPasswordHash());
                ps.setString(6, user.getPhone());
                ps.setString(7, user.getRollNo());
                ps.setString(8, user.getRole().name());
                ps.setString(9, activeRole.name());
                ps.setBoolean(10, user.isActive());
                ps.setBoolean(11, user.isEmailVerified());
                ps.setBoolean(12, user.isPhoneVerified());
                return ps;
            }, keyHolder);
            user.setId(keyHolder.getKey().longValue());

            // Also insert into user_roles
            grantRoleInternal(user.getId(), user.getRole());
            if (user.getRoles() != null) {
                for (Role r : user.getRoles()) {
                    grantRoleInternal(user.getId(), r);
                }
            }
        } else {
            jdbcTemplate.update(
                    "UPDATE users SET tenant_id = ?, outlet_id = ?, name = ?, email = ?, password_hash = ?, "
                            + "phone = ?, roll_no = ?, role = ?, active_role = ?, is_active = ?, email_verified = ?, "
                            + "phone_verified = ? WHERE id = ?",
                    user.getTenantId(), user.getOutletId(), user.getName(), user.getEmail(), user.getPasswordHash(),
                    user.getPhone(), user.getRollNo(), user.getRole().name(),
                    (user.getActiveRole() != null ? user.getActiveRole() : user.getRole()).name(),
                    user.isActive(), user.isEmailVerified(), user.isPhoneVerified(),
                    user.getId());
        }
        return findById(user.getId()).orElseThrow();
    }

    @Override
    public void setActive(Long id, boolean active) {
        jdbcTemplate.update("UPDATE users SET is_active = ? WHERE id = ?", active, id);
    }

    @Override
    public void detachFromOutlet(Long id) {
        jdbcTemplate.update("UPDATE users SET outlet_id = NULL, is_active = FALSE WHERE id = ?", id);
    }

    @Override
    public void markEmailVerified(Long id) {
        jdbcTemplate.update("UPDATE users SET email_verified = TRUE WHERE id = ?", id);
    }

    @Override
    public void markPhoneVerified(Long id) {
        jdbcTemplate.update("UPDATE users SET phone_verified = TRUE WHERE id = ?", id);
    }

    @Override
    public void anonymize(Long id, String anonymizedEmail, String anonymizedPasswordHash) {
        jdbcTemplate.update(
                "UPDATE users SET name = 'Deleted User', email = ?, password_hash = ?, phone = NULL, "
                        + "roll_no = NULL, is_active = FALSE WHERE id = ?",
                anonymizedEmail, anonymizedPasswordHash, id);
    }

    // ---- Multi-role support ----

    @Override
    public Set<Role> findRoles(Long userId) {
        List<String> roleNames = jdbcTemplate.queryForList(
                "SELECT role FROM user_roles WHERE user_id = ?", String.class, userId);
        Set<Role> result = EnumSet.noneOf(Role.class);
        for (String name : roleNames) {
            result.add(Role.valueOf(name));
        }
        return result;
    }

    @Override
    public void grantRole(Long userId, Role role, Long actorUserId) {
        // INSERT IGNORE — no-op if already granted
        jdbcTemplate.update(
                "INSERT IGNORE INTO user_roles (user_id, role, granted_by) VALUES (?, ?, ?)",
                userId, role.name(), actorUserId);
        // Audit
        jdbcTemplate.update(
                "INSERT INTO role_audit (user_id, role, action, actor_user_id) VALUES (?, ?, 'GRANT', ?)",
                userId, role.name(), actorUserId);
    }

    @Override
    public void revokeRole(Long userId, Role role, Long actorUserId) {
        int affected = jdbcTemplate.update(
                "DELETE FROM user_roles WHERE user_id = ? AND role = ?",
                userId, role.name());
        if (affected > 0) {
            jdbcTemplate.update(
                    "INSERT INTO role_audit (user_id, role, action, actor_user_id) VALUES (?, ?, 'REVOKE', ?)",
                    userId, role.name(), actorUserId);
        }
    }

    @Override
    public void updateActiveRole(Long userId, Role activeRole) {
        jdbcTemplate.update(
                "UPDATE users SET active_role = ? WHERE id = ?",
                activeRole.name(), userId);
    }

    // ---- Internal helpers ----

    /** Populates user.roles from the user_roles table. */
    private User loadRoles(User user) {
        user.setRoles(findRoles(user.getId()));
        return user;
    }

    /** Internal grant without audit (used during initial user creation). */
    private void grantRoleInternal(Long userId, Role role) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO user_roles (user_id, role) VALUES (?, ?)",
                userId, role.name());
    }
}
