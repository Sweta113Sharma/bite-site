package com.bitesite.dao;

import com.bitesite.model.CategoryDefaultImage;
import com.bitesite.model.CategoryImage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CategoryImageDaoImpl implements CategoryImageDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<CategoryImage> ROW_MAPPER = (rs, rowNum) -> CategoryImage.builder()
            .categoryId(rs.getLong("category_id"))
            .tenantId(rs.getLong("tenant_id"))
            .outletId(rs.getLong("outlet_id"))
            .imagePath(rs.getString("image_path"))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .updatedAt(rs.getObject("updated_at", LocalDateTime.class))
            .build();

    private static final RowMapper<CategoryDefaultImage> DEFAULT_ROW_MAPPER =
            (rs, rowNum) -> CategoryDefaultImage.builder()
                    .nameKey(rs.getString("name_key"))
                    .displayName(rs.getString("display_name"))
                    .imagePath(rs.getString("image_path"))
                    .uploadedBy(rs.getObject("uploaded_by", Long.class))
                    .createdAt(rs.getObject("created_at", LocalDateTime.class))
                    .updatedAt(rs.getObject("updated_at", LocalDateTime.class))
                    .build();

    // ── An outlet's own images ────────────────────────────────────────────────

    @Override
    public List<CategoryImage> findByOutlet(Long outletId, Long tenantId) {
        // The menu screen's one extra query. Covered by idx_category_images_outlet, and it
        // returns at most one row per category — single digits for a real canteen.
        return jdbcTemplate.query(
                "SELECT * FROM category_images WHERE outlet_id = ? AND tenant_id = ?",
                ROW_MAPPER, outletId, tenantId);
    }

    @Override
    public Optional<CategoryImage> findByCategoryId(Long categoryId, Long tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM category_images WHERE category_id = ? AND tenant_id = ?",
                ROW_MAPPER, categoryId, tenantId).stream().findFirst();
    }

    @Override
    public void upsert(CategoryImage image) {
        // ON DUPLICATE KEY rather than a read-then-branch: category_id is the primary key,
        // so the database already knows whether this is a replacement, and doing it in one
        // statement removes the window where two uploads could both decide they are inserts.
        jdbcTemplate.update(
                "INSERT INTO category_images (category_id, tenant_id, outlet_id, image_path) "
                        + "VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE image_path = VALUES(image_path)",
                image.getCategoryId(), image.getTenantId(), image.getOutletId(), image.getImagePath());
    }

    @Override
    public void delete(Long categoryId, Long tenantId) {
        jdbcTemplate.update("DELETE FROM category_images WHERE category_id = ? AND tenant_id = ?",
                categoryId, tenantId);
    }

    // ── Platform defaults ─────────────────────────────────────────────────────

    @Override
    public List<CategoryDefaultImage> findAllDefaults() {
        return jdbcTemplate.query("SELECT * FROM category_default_images", DEFAULT_ROW_MAPPER);
    }

    @Override
    public void upsertDefault(CategoryDefaultImage image) {
        jdbcTemplate.update(
                "INSERT INTO category_default_images (name_key, display_name, image_path, uploaded_by) "
                        + "VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE image_path = VALUES(image_path), "
                        + "display_name = VALUES(display_name), uploaded_by = VALUES(uploaded_by)",
                image.getNameKey(), image.getDisplayName(), image.getImagePath(), image.getUploadedBy());
    }

    @Override
    public void deleteDefault(String nameKey) {
        jdbcTemplate.update("DELETE FROM category_default_images WHERE name_key = ?", nameKey);
    }

    @Override
    public List<UnillustratedCategory> findCategoriesWithoutDefault() {
        // LOWER(TRIM(name)) mirrors CategoryImageService.normalise exactly. If that ever
        // changes, this has to change with it, which is why the service holds the only
        // other copy of the rule rather than it being spread across call sites.
        //
        // MIN(name) picks one spelling to show when outlets disagree on capitalisation.
        // Arbitrary but stable, and only ever used as a label.
        return jdbcTemplate.query(
                "SELECT LOWER(TRIM(c.name)) AS name_key, MIN(c.name) AS display_name, "
                        + "COUNT(DISTINCT c.outlet_id) AS outlet_count "
                        + "FROM categories c "
                        + "LEFT JOIN category_default_images d ON d.name_key = LOWER(TRIM(c.name)) "
                        + "WHERE d.name_key IS NULL "
                        + "GROUP BY LOWER(TRIM(c.name)) "
                        + "ORDER BY outlet_count DESC, name_key",
                (rs, n) -> new UnillustratedCategory(
                        rs.getString("name_key"),
                        rs.getString("display_name"),
                        rs.getInt("outlet_count")));
    }
}
