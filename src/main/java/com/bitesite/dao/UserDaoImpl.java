package com.bitesite.dao;

import com.bitesite.model.Role;
import com.bitesite.model.User;
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
            .active(rs.getBoolean("is_active"))
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .build();

    @Override
    public Optional<User> findById(Long id) {
        return jdbcTemplate.query("SELECT * FROM users WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jdbcTemplate.query("SELECT * FROM users WHERE email = ?", ROW_MAPPER, email)
                .stream().findFirst();
    }

    @Override
    public boolean existsByEmail(String email) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        return count != null && count > 0;
    }

    @Override
    public List<User> findByTenantId(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM users WHERE tenant_id = ? ORDER BY created_at DESC", ROW_MAPPER, tenantId);
    }

    @Override
    public List<User> findPlatformUsers() {
        return jdbcTemplate.query(
                "SELECT * FROM users WHERE tenant_id IS NULL ORDER BY created_at DESC", ROW_MAPPER);
    }

    @Override
    public User save(User user) {
        if (user.getId() == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO users (tenant_id, outlet_id, name, email, password_hash, phone, roll_no, role, is_active) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setObject(1, user.getTenantId());
                ps.setObject(2, user.getOutletId());
                ps.setString(3, user.getName());
                ps.setString(4, user.getEmail());
                ps.setString(5, user.getPasswordHash());
                ps.setString(6, user.getPhone());
                ps.setString(7, user.getRollNo());
                ps.setString(8, user.getRole().name());
                ps.setBoolean(9, user.isActive());
                return ps;
            }, keyHolder);
            user.setId(keyHolder.getKey().longValue());
        } else {
            jdbcTemplate.update(
                    "UPDATE users SET tenant_id = ?, outlet_id = ?, name = ?, email = ?, password_hash = ?, "
                            + "phone = ?, roll_no = ?, role = ?, is_active = ? WHERE id = ?",
                    user.getTenantId(), user.getOutletId(), user.getName(), user.getEmail(), user.getPasswordHash(),
                    user.getPhone(), user.getRollNo(), user.getRole().name(), user.isActive(), user.getId());
        }
        return findById(user.getId()).orElseThrow();
    }

    @Override
    public void setActive(Long id, boolean active) {
        jdbcTemplate.update("UPDATE users SET is_active = ? WHERE id = ?", active, id);
    }
}
