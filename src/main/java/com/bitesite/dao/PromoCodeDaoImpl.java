package com.bitesite.dao;

import com.bitesite.model.PromoCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PromoCodeDaoImpl implements PromoCodeDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<PromoCode> ROW_MAPPER = (rs, rowNum) -> PromoCode.builder()
            .id(rs.getLong("id"))
            .code(rs.getString("code"))
            .description(rs.getString("description"))
            .discountType(PromoCode.Type.valueOf(rs.getString("discount_type")))
            .discountValue(rs.getBigDecimal("discount_value"))
            .maxDiscount(rs.getBigDecimal("max_discount"))
            .minOrderValue(rs.getBigDecimal("min_order_value"))
            .fundedBy(PromoCode.Funder.valueOf(rs.getString("funded_by")))
            // getObject, not getLong: a null scope means "everywhere", and getLong would
            // turn that into college zero.
            .tenantId(rs.getObject("tenant_id", Long.class))
            .outletId(rs.getObject("outlet_id", Long.class))
            .validFrom(rs.getObject("valid_from", LocalDateTime.class))
            .validUntil(rs.getObject("valid_until", LocalDateTime.class))
            .maxRedemptions(rs.getObject("max_redemptions", Integer.class))
            .maxPerUser(rs.getObject("max_per_user", Integer.class))
            .active(rs.getBoolean("is_active"))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .build();

    /**
     * A redemption only counts while the order it belongs to is still alive.
     *
     * <p>The row is written when the order is created, before the student has paid, so it
     * reserves a slot the moment somebody starts checking out — two people cannot both
     * take the last one. But an abandoned or declined checkout must not keep it: without
     * this clause a fifty-use campaign could be exhausted by fifty people who never paid.
     * Marking the order failed or expired releases the slot on its own, with no cleanup job
     * and no counter to drift, and the redemption row stays for the record.
     */
    private static final String LIVE_REDEMPTION =
            "EXISTS (SELECT 1 FROM orders o WHERE o.id = r.order_id "
                    + "AND o.status NOT IN ('PAYMENT_FAILED','EXPIRED','CANCELLED'))";

    /** Listing carries the live count, so an admin sees usage without a second query per row. */
    private static final String SELECT_WITH_COUNT = """
            SELECT p.*, (SELECT COUNT(*) FROM promo_redemptions r
                          WHERE r.promo_code_id = p.id AND %s) AS uses
            FROM promo_codes p
            """.formatted(LIVE_REDEMPTION);

    @Override
    public List<PromoCode> findAll() {
        return jdbcTemplate.query(SELECT_WITH_COUNT + " ORDER BY p.created_at DESC", (rs, i) -> {
            PromoCode code = ROW_MAPPER.mapRow(rs, i);
            code.setRedemptionCount(rs.getInt("uses"));
            return code;
        });
    }

    @Override
    public Optional<PromoCode> findById(Long id) {
        return jdbcTemplate.query(SELECT_WITH_COUNT + " WHERE p.id = ?", (rs, i) -> {
            PromoCode code = ROW_MAPPER.mapRow(rs, i);
            code.setRedemptionCount(rs.getInt("uses"));
            return code;
        }, id).stream().findFirst();
    }

    @Override
    public Optional<PromoCode> findByCode(String code) {
        // Stored uppercase and compared uppercase: students type codes off a poster.
        return jdbcTemplate.query("SELECT * FROM promo_codes WHERE code = ?",
                ROW_MAPPER, code == null ? null : code.trim().toUpperCase()).stream().findFirst();
    }

    @Override
    public PromoCode save(PromoCode code) {
        if (code.getId() == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO promo_codes (code, description, discount_type, discount_value,
                            max_discount, min_order_value, funded_by, tenant_id, outlet_id,
                            valid_from, valid_until, max_redemptions, max_per_user, is_active)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                bind(ps, code);
                return ps;
            }, keyHolder);
            code.setId(keyHolder.getKey().longValue());
        } else {
            jdbcTemplate.update("""
                    UPDATE promo_codes SET code = ?, description = ?, discount_type = ?, discount_value = ?,
                        max_discount = ?, min_order_value = ?, funded_by = ?, tenant_id = ?, outlet_id = ?,
                        valid_from = ?, valid_until = ?, max_redemptions = ?, max_per_user = ?, is_active = ?
                    WHERE id = ?
                    """,
                    code.getCode().trim().toUpperCase(), code.getDescription(), code.getDiscountType().name(),
                    code.getDiscountValue(), code.getMaxDiscount(), code.getMinOrderValue(),
                    code.getFundedBy().name(), code.getTenantId(), code.getOutletId(),
                    code.getValidFrom(), code.getValidUntil(), code.getMaxRedemptions(),
                    code.getMaxPerUser(), code.isActive(), code.getId());
        }
        return findById(code.getId()).orElseThrow();
    }

    private static void bind(PreparedStatement ps, PromoCode c) throws java.sql.SQLException {
        ps.setString(1, c.getCode().trim().toUpperCase());
        ps.setString(2, c.getDescription());
        ps.setString(3, c.getDiscountType().name());
        ps.setBigDecimal(4, c.getDiscountValue());
        ps.setBigDecimal(5, c.getMaxDiscount());
        ps.setBigDecimal(6, c.getMinOrderValue());
        ps.setString(7, c.getFundedBy().name());
        setLongOrNull(ps, 8, c.getTenantId());
        setLongOrNull(ps, 9, c.getOutletId());
        ps.setObject(10, c.getValidFrom());
        ps.setObject(11, c.getValidUntil());
        setIntOrNull(ps, 12, c.getMaxRedemptions());
        setIntOrNull(ps, 13, c.getMaxPerUser());
        ps.setBoolean(14, c.isActive());
    }

    /** setLong(0) for a null would scope a code to a college that does not exist. */
    private static void setLongOrNull(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setIntOrNull(PreparedStatement ps, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    @Override
    public void setActive(Long id, boolean active) {
        jdbcTemplate.update("UPDATE promo_codes SET is_active = ? WHERE id = ?", active, id);
    }

    @Override
    public void delete(Long id) {
        // Redemptions reference the code, so a used one cannot be deleted. That is correct:
        // the record of what a student was given must outlive the campaign. Switch it off.
        jdbcTemplate.update("DELETE FROM promo_codes WHERE id = ?", id);
    }

    @Override
    public int countRedemptions(Long promoCodeId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM promo_redemptions r WHERE r.promo_code_id = ? AND " + LIVE_REDEMPTION,
                Integer.class, promoCodeId);
        return n == null ? 0 : n;
    }

    @Override
    public int countRedemptionsByUser(Long promoCodeId, Long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM promo_redemptions r WHERE r.promo_code_id = ? AND r.user_id = ? "
                        + "AND " + LIVE_REDEMPTION,
                Integer.class, promoCodeId, userId);
        return n == null ? 0 : n;
    }

    @Override
    public int countAllRedemptions(Long promoCodeId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM promo_redemptions WHERE promo_code_id = ?", Integer.class, promoCodeId);
        return n == null ? 0 : n;
    }

    @Override
    public void recordRedemption(Long promoCodeId, Long orderId, Long userId, BigDecimal discountAmount) {
        jdbcTemplate.update(
                "INSERT INTO promo_redemptions (promo_code_id, order_id, user_id, discount_amount) "
                        + "VALUES (?, ?, ?, ?)",
                promoCodeId, orderId, userId, discountAmount);
    }
}
