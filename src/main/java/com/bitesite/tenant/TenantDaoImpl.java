package com.bitesite.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class TenantDaoImpl implements TenantDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Tenant> ROW_MAPPER = (rs, rowNum) -> Tenant.builder()
            .id(rs.getLong("id"))
            .name(rs.getString("name"))
            .logoPath(rs.getString("logo_path"))
            .status(TenantStatus.valueOf(rs.getString("status")))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .updatedAt(rs.getObject("updated_at", LocalDateTime.class))
            .build();

    @Override
    public Optional<Tenant> findById(Long id) {
        List<Tenant> results = jdbcTemplate.query("SELECT * FROM tenants WHERE id = ?", ROW_MAPPER, id);
        return results.stream().findFirst();
    }

    @Override
    public List<Tenant> findAll() {
        return jdbcTemplate.query("SELECT * FROM tenants ORDER BY created_at DESC", ROW_MAPPER);
    }

    @Override
    public List<Tenant> findActive() {
        return jdbcTemplate.query(
                "SELECT * FROM tenants WHERE status = 'ACTIVE' ORDER BY name", ROW_MAPPER);
    }

    @Override
    public Tenant save(Tenant tenant) {
        if (tenant.getId() == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO tenants (name, logo_path, status) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, tenant.getName());
                ps.setString(2, tenant.getLogoPath());
                ps.setString(3, tenant.getStatus().name());
                return ps;
            }, keyHolder);
            tenant.setId(keyHolder.getKey().longValue());
        } else {
            jdbcTemplate.update(
                    "UPDATE tenants SET name = ?, logo_path = ?, status = ? WHERE id = ?",
                    tenant.getName(), tenant.getLogoPath(), tenant.getStatus().name(), tenant.getId());
        }
        return findById(tenant.getId()).orElseThrow();
    }

    @Override
    public void updateName(Long id, String name) {
        jdbcTemplate.update("UPDATE tenants SET name = ? WHERE id = ?", name, id);
    }

    @Override
    public void updateLogoPath(Long id, String logoPath) {
        jdbcTemplate.update("UPDATE tenants SET logo_path = ? WHERE id = ?", logoPath, id);
    }

    @Override
    public void updateStatus(Long id, TenantStatus status) {
        jdbcTemplate.update("UPDATE tenants SET status = ? WHERE id = ?", status.name(), id);
    }

    @Override
    public int countOrders(Long tenantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE tenant_id = ?", Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    /**
     * Both places a role can live are checked. users.role is the active one, but a person
     * can hold USER in user_roles while operating as something else, and a student account
     * missed by this count is a person whose account a cleanup button would delete.
     */
    @Override
    public int countStudents(Long tenantId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM users u
                WHERE u.tenant_id = ?
                  AND (u.role = 'USER'
                       OR EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = u.id AND r.role = 'USER'))
                """, Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    @Override
    public List<Long> findStaffUserIds(Long tenantId) {
        return jdbcTemplate.queryForList("SELECT id FROM users WHERE tenant_id = ?", Long.class, tenantId);
    }

    /**
     * Every foreign key into a tenant is ON DELETE NO ACTION, so nothing cascades on its
     * own and the order below is the whole correctness argument. Children first, parents
     * after, and within that: menu items before the categories they point at, carts before
     * the menu items they point at.
     *
     * <p>Two things are detached rather than destroyed. The onboarding lead is a record of
     * a conversation that happened whether or not the college survived it. And the audit
     * log is the account of who did what — deleting the college must not delete the
     * evidence of who deleted it, which is why tenant_id is nulled instead. Both columns
     * are nullable for exactly this reason.
     *
     * <p>Staff accounts do go, because they exist only to sign in to this college and a
     * tenant-scoped account with no tenant is not a coherent row. Their fingerprints on the
     * audit log survive as a null actor rather than taking the entry with them.
     *
     * <p>Orders, payments and order_items are absent from this list on purpose: the service
     * refuses the whole operation when any order exists, so there are none to delete. If
     * that guard is ever removed, this method is wrong, not incomplete.
     */
    @Override
    @Transactional
    public void delete(Long id) {
        // Carts reference menu items, outlets and users, so they unwind first.
        jdbcTemplate.update("""
                DELETE FROM saved_cart_items
                WHERE menu_item_id IN (SELECT id FROM menu_items WHERE tenant_id = ?)
                   OR user_id IN (SELECT id FROM users WHERE tenant_id = ?)
                """, id, id);
        jdbcTemplate.update("""
                DELETE FROM saved_carts
                WHERE outlet_id IN (SELECT id FROM outlets WHERE tenant_id = ?)
                   OR user_id IN (SELECT id FROM users WHERE tenant_id = ?)
                """, id, id);

        // Then everything that points at a staff account. Both the tenant-scoped and the
        // user-scoped rows, because tenant_id is nullable on some of these and a row with
        // a null tenant would otherwise survive to block the users delete below.
        jdbcTemplate.update("""
                DELETE FROM grievances
                WHERE tenant_id = ? OR raised_by_user_id IN (SELECT id FROM users WHERE tenant_id = ?)
                """, id, id);
        jdbcTemplate.update("""
                DELETE FROM data_requests
                WHERE tenant_id = ? OR user_id IN (SELECT id FROM users WHERE tenant_id = ?)
                """, id, id);
        jdbcTemplate.update("DELETE FROM consents WHERE user_id IN (SELECT id FROM users WHERE tenant_id = ?)", id);
        jdbcTemplate.update("DELETE FROM otp_codes WHERE user_id IN (SELECT id FROM users WHERE tenant_id = ?)", id);
        jdbcTemplate.update("DELETE FROM push_subscriptions WHERE user_id IN (SELECT id FROM users WHERE tenant_id = ?)", id);
        jdbcTemplate.update("DELETE FROM fcm_tokens WHERE user_id IN (SELECT id FROM users WHERE tenant_id = ?)", id);
        jdbcTemplate.update("DELETE FROM role_audit WHERE user_id IN (SELECT id FROM users WHERE tenant_id = ?)", id);
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE tenant_id = ?)", id);
        // These three name whoever performed an action, who may sit outside this college,
        // so the row stays and only the pointer goes.
        jdbcTemplate.update("UPDATE role_audit SET actor_user_id = NULL WHERE actor_user_id IN (SELECT id FROM users WHERE tenant_id = ?)", id);
        jdbcTemplate.update("UPDATE user_roles SET granted_by = NULL WHERE granted_by IN (SELECT id FROM users WHERE tenant_id = ?)", id);
        jdbcTemplate.update("UPDATE audit_log SET actor_user_id = NULL WHERE actor_user_id IN (SELECT id FROM users WHERE tenant_id = ?)", id);

        // Users must go BEFORE outlets: users.outlet_id is a foreign key at the outlet, so
        // deleting the outlet first fails. Found the hard way — the first version of this
        // method had the two the other way round and threw on any college with staff.
        jdbcTemplate.update("DELETE FROM users WHERE tenant_id = ?", id);

        // Catalog, innermost first: items point at categories, both point at the outlet.
        jdbcTemplate.update("DELETE FROM menu_items WHERE tenant_id = ?", id);
        jdbcTemplate.update("DELETE FROM categories WHERE tenant_id = ?", id);
        jdbcTemplate.update("DELETE FROM outlets WHERE tenant_id = ?", id);

        jdbcTemplate.update("DELETE FROM tech_config WHERE tenant_id = ?", id);

        // Kept, detached. The lead is the record of a conversation that happened whether or
        // not the college survived it, and the audit log must outlive the thing it audits —
        // including the DELETE entry the service is about to write.
        jdbcTemplate.update("UPDATE onboarding_pipeline SET tenant_id = NULL WHERE tenant_id = ?", id);
        jdbcTemplate.update("UPDATE audit_log SET tenant_id = NULL WHERE tenant_id = ?", id);

        jdbcTemplate.update("DELETE FROM tenants WHERE id = ?", id);
    }
}
