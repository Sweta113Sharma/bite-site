package com.bitesite.dao;

import com.bitesite.model.CategoryDefaultImage;
import com.bitesite.model.CategoryImage;

import java.util.List;
import java.util.Optional;

/**
 * Reads and writes both rungs of the category-artwork fallback: an outlet's own images and
 * the platform defaults an admin sets.
 *
 * <p>The outlet-scoped methods take an explicit {@code tenantId} like every other DAO here.
 * The default-image methods do not, and cannot: a platform default belongs to no tenant by
 * design, and is only ever written from the admin console behind {@code SUPER_ADMIN}.
 */
public interface CategoryImageDao {

    // ── An outlet's own images ────────────────────────────────────────────────

    /** Every image one outlet has set, for the menu screen's single lookup. */
    List<CategoryImage> findByOutlet(Long outletId, Long tenantId);

    Optional<CategoryImage> findByCategoryId(Long categoryId, Long tenantId);

    /** Insert or replace — a category has at most one image, enforced by the primary key. */
    void upsert(CategoryImage image);

    void delete(Long categoryId, Long tenantId);

    // ── Platform defaults ─────────────────────────────────────────────────────

    /** Every default, for the in-memory map the menu resolves against. */
    List<CategoryDefaultImage> findAllDefaults();

    void upsertDefault(CategoryDefaultImage image);

    void deleteDefault(String nameKey);

    /**
     * Category names in use across the platform that have no default image yet, newest
     * first, so the admin screen surfaces a category nobody has illustrated.
     *
     * <p>Grouped by the normalised name rather than listed per row: twelve outlets each
     * with a "Snacks" is one thing for an admin to deal with, not twelve.
     */
    List<UnillustratedCategory> findCategoriesWithoutDefault();

    /**
     * A category name nobody has set a platform image for.
     *
     * @param nameKey     normalised name, the key an upload would write
     * @param displayName the name as some canteen actually typed it
     * @param outletCount how many outlets use it, so the admin can tell a widespread
     *                    category from a one-off
     */
    record UnillustratedCategory(String nameKey, String displayName, int outletCount) {
    }
}
