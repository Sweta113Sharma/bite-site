package com.bitesite.service;

import com.bitesite.dao.CategoryImageDao;
import com.bitesite.dao.OutletDao;
import com.bitesite.dao.PlatformSettingsDao;
import com.bitesite.model.Category;
import com.bitesite.model.CategoryDefaultImage;
import com.bitesite.model.CategoryImage;
import com.bitesite.model.Outlet;
import com.bitesite.dao.CategoryDao;
import com.bitesite.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves which picture a menu category should wear, and owns the two ways one gets set.
 *
 * <p>The order is outlet's own image, then the platform default for that category's name,
 * then nothing — at which point the caller keeps doing what it did before this existed
 * (the first item's photo, then an illustration). Each rung only applies when the one
 * above it is absent, so a canteen that uploads nothing sees no change at all.
 *
 * <p><strong>Cost.</strong> Rendering a menu adds exactly one query: every image for one
 * outlet, indexed, single-digit rows. The defaults are not queried per request — they are
 * a platform-wide table of maybe twenty rows that changes approximately never, so they are
 * held in memory and invalidated on write, for the same reason
 * {@link com.bitesite.tenant.TenantCache} exists: in production the database is in another
 * region, and a per-request round trip for static data is the thing worth removing.
 */
@Service
@RequiredArgsConstructor
public class CategoryImageService {

    /**
     * Backstop only, not the invalidation mechanism — identical reasoning to TenantCache.
     * Writes invalidate explicitly, so on the single instance this runs on today the TTL
     * never decides anything; it exists for the day a second instance cannot be told.
     */
    private static final Duration DEFAULTS_TTL = Duration.ofMinutes(5);

    /** A chip is a ~56px circle; 160 covers it on a 3x-DPR phone with a little headroom. */
    private static final int CHIP_EDGE_PX = 160;

    /**
     * The platform_settings key for the platform's "All Dishes" picture. "All Dishes" is
     * not a category name, so it cannot live in category_default_images without reserving
     * a name no canteen may use; a setting has no such collision.
     */
    static final String ALL_DISHES_DEFAULT_KEY = "category_image.all_dishes";

    private final CategoryImageDao categoryImageDao;
    private final CategoryDao categoryDao;
    private final OutletDao outletDao;
    private final PlatformSettingsDao platformSettingsDao;
    private final FileStorageService fileStorageService;

    private final AtomicReference<DefaultsSnapshot> defaults = new AtomicReference<>();

    /** {@code allDishes} is null when no admin has set one. */
    private record DefaultsSnapshot(Map<String, String> byNameKey, String allDishes, long expiresAtMillis) {
        boolean isFresh() {
            return System.currentTimeMillis() < expiresAtMillis;
        }
    }

    /**
     * The match key for a platform default: lowercased and trimmed.
     *
     * <p>The single source of this rule. {@code CategoryImageDaoImpl.findCategoriesWithoutDefault}
     * holds the only other copy, as SQL, and the two must agree — if this ever becomes
     * cleverer than {@code LOWER(TRIM(...))}, that query has to move in step or the admin
     * screen will keep offering a category that already has an image.
     */
    public static String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    // ── Resolution ────────────────────────────────────────────────────────────

    /**
     * The artwork for a set of categories, keyed by category name.
     *
     * <p>Takes the caller's own name → id mapping rather than re-reading the categories
     * table. The menu screen has already loaded every item for the outlet, and each item
     * carries both {@code categoryId} and {@code category}, so the mapping is free there —
     * whereas {@code CategoryDao.findByOutlet} would have cost a second query carrying a
     * correlated item-count subquery per row that the menu has no use for.
     *
     * <p>So rendering a menu costs exactly one extra query: this outlet's images. The
     * defaults come from memory.
     *
     * <p>A category with no image at either rung is simply absent from the returned map, so
     * a caller asking for one gets null and falls through to its existing behaviour.
     */
    public Map<String, String> resolveForOutlet(Long outletId, Long tenantId, Map<String, Long> categoryIdByName) {
        if (categoryIdByName.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> own = new HashMap<>();
        for (CategoryImage image : categoryImageDao.findByOutlet(outletId, tenantId)) {
            own.put(image.getCategoryId(), image.getImagePath());
        }
        Map<String, String> platformDefaults = defaults();

        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, Long> entry : categoryIdByName.entrySet()) {
            String path = own.get(entry.getValue());
            if (path == null) {
                path = platformDefaults.get(normalise(entry.getKey()));
            }
            if (path != null) {
                resolved.put(entry.getKey(), path);
            }
        }
        return resolved;
    }

    /**
     * The picture each category chip should actually load, already sized for a chip.
     *
     * <p>The whole fallback chain in one place — outlet's own, platform default, then the
     * first item's photo as this screen has always done — so the template asks one question
     * instead of working through three conditions. A category with nothing at any rung is
     * absent, and the chip falls back to its glyph.
     *
     * <p>Every answer goes through {@code thumbnailUrl}. That matters most for the last
     * rung: menu photos are stored at up to 1600px and a chip draws them at ~56px, so
     * several full-size files were being downloaded and decoded per menu load to render
     * thumbnails. On Cloudinary (what production uses) this is a URL rewrite the CDN
     * serves; on local disk it is a no-op and the file is unchanged.
     */
    public Map<String, String> chipImages(Long outletId, Long tenantId,
            Map<String, Long> categoryIdByName,
            Map<String, ? extends List<? extends MenuItemPhoto>> itemsByCategory) {
        Map<String, String> resolved = new HashMap<>(
                resolveForOutlet(outletId, tenantId, categoryIdByName));

        // Third rung: the first item's photo, exactly as the chips did before any of this.
        for (Map.Entry<String, ? extends List<? extends MenuItemPhoto>> entry : itemsByCategory.entrySet()) {
            if (resolved.containsKey(entry.getKey()) || entry.getValue().isEmpty()) {
                continue;
            }
            MenuItemPhoto first = entry.getValue().get(0);
            if (!first.usesIllustration() && first.getPhotoPath() != null) {
                resolved.put(entry.getKey(), first.getPhotoPath());
            }
        }

        Map<String, String> sized = new HashMap<>();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            sized.put(entry.getKey(), fileStorageService.thumbnailUrl(entry.getValue(), CHIP_EDGE_PX));
        }
        return sized;
    }

    /**
     * What this service needs of a menu item, which is only its photo. Narrowed to an
     * interface so the service does not depend on the whole MenuItem model to read two
     * fields — {@link com.bitesite.model.MenuItem} satisfies it as it stands.
     */
    public interface MenuItemPhoto {
        String getPhotoPath();

        boolean usesIllustration();
    }

    /**
     * Same resolution for the management screens, which hold real {@link Category} rows
     * and want an answer per category including the ones with no image at all.
     */
    public Map<Long, String> resolveForCategories(List<Category> categories, Long outletId, Long tenantId) {
        if (categories.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> own = new HashMap<>();
        for (CategoryImage image : categoryImageDao.findByOutlet(outletId, tenantId)) {
            own.put(image.getCategoryId(), image.getImagePath());
        }
        Map<String, String> platformDefaults = defaults();

        Map<Long, String> resolved = new HashMap<>();
        for (Category category : categories) {
            String path = own.get(category.getId());
            if (path == null) {
                path = platformDefaults.get(normalise(category.getName()));
            }
            if (path != null) {
                resolved.put(category.getId(), path);
            }
        }
        return resolved;
    }

    /**
     * Which categories carry an image the OUTLET set, as opposed to one inherited from the
     * platform. The management screen needs the distinction: "replace yours" and "override
     * the default" are different offers, and only the first can be removed.
     */
    public java.util.Set<Long> outletOwnedCategoryIds(Long outletId, Long tenantId) {
        java.util.Set<Long> owned = new java.util.HashSet<>();
        for (CategoryImage image : categoryImageDao.findByOutlet(outletId, tenantId)) {
            owned.add(image.getCategoryId());
        }
        return owned;
    }

    /** The platform defaults as a name-key → path map, from memory unless stale. */
    private Map<String, String> defaults() {
        return snapshot().byNameKey();
    }

    private DefaultsSnapshot snapshot() {
        DefaultsSnapshot current = defaults.get();
        if (current != null && current.isFresh()) {
            return current;
        }
        Map<String, String> loaded = new HashMap<>();
        for (CategoryDefaultImage image : categoryImageDao.findAllDefaults()) {
            loaded.put(image.getNameKey(), image.getImagePath());
        }
        // Blank is treated as unset: clearing writes NULL, but a hand-edited row should not
        // turn into an <img> with an empty src.
        String allDishes = platformSettingsDao.findAll().get(ALL_DISHES_DEFAULT_KEY);
        DefaultsSnapshot fresh = new DefaultsSnapshot(Map.copyOf(loaded),
                allDishes == null || allDishes.isBlank() ? null : allDishes,
                System.currentTimeMillis() + DEFAULTS_TTL.toMillis());
        defaults.set(fresh);
        return fresh;
    }

    // ── The "All Dishes" chip ─────────────────────────────────────────────────

    /**
     * What the "All Dishes" chip shows and where that came from, for the management screen.
     *
     * @param path        the image students see, or null for the bundled illustration
     * @param outletOwned true when the outlet uploaded it, so the screen offers Remove
     */
    public record AllDishesImage(String path, boolean outletOwned) {
    }

    /**
     * The "All Dishes" picture for the student menu, sized for a chip, or null for the
     * bundled illustration. Same order as a real category: the outlet's own, then the
     * platform default. Costs no query: the outlet row is already loaded, and the default
     * comes from the same in-memory snapshot as the category defaults.
     */
    public String allDishesChipImage(Outlet outlet) {
        String path = outlet.getAllDishesImagePath();
        if (path == null) {
            path = snapshot().allDishes();
        }
        return path == null ? null : fileStorageService.thumbnailUrl(path, CHIP_EDGE_PX);
    }

    public AllDishesImage allDishesImageForOutlet(Long outletId, Long tenantId) {
        String own = outletDao.findByIdAndTenantId(outletId, tenantId)
                .map(Outlet::getAllDishesImagePath)
                .orElse(null);
        if (own != null) {
            return new AllDishesImage(own, true);
        }
        return new AllDishesImage(snapshot().allDishes(), false);
    }

    /**
     * Stores an outlet's own "All Dishes" picture. The outlet is re-read scoped to the
     * tenant first, same as {@link #setOutletImage}, so an id from another college is a
     * not-found rather than a write.
     */
    @Transactional
    public void setOutletAllDishesImage(Long outletId, Long tenantId, MultipartFile file) {
        Outlet outlet = outletDao.findByIdAndTenantId(outletId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Outlet not found"));
        String path = fileStorageService.storeCategoryImage(tenantId, file);
        outletDao.updateAllDishesImagePath(outlet.getId(), tenantId, path);
    }

    /** Drops the outlet's own, falling back to the platform default or the illustration. */
    @Transactional
    public void clearOutletAllDishesImage(Long outletId, Long tenantId) {
        outletDao.updateAllDishesImagePath(outletId, tenantId, null);
    }

    /** The platform's "All Dishes" picture, or null. For the admin screen. */
    public String defaultAllDishesImage() {
        return snapshot().allDishes();
    }

    /** Sets the platform's "All Dishes" picture. Admin console only, tenant-less by design. */
    @Transactional
    public void setDefaultAllDishesImage(MultipartFile file) {
        String path = fileStorageService.storeCategoryImage(null, file);
        platformSettingsDao.upsert(ALL_DISHES_DEFAULT_KEY, path);
        invalidateDefaults();
    }

    @Transactional
    public void clearDefaultAllDishesImage() {
        platformSettingsDao.upsert(ALL_DISHES_DEFAULT_KEY, null);
        invalidateDefaults();
    }

    // ── An outlet setting its own ─────────────────────────────────────────────

    /**
     * Stores an outlet's image for one of its own categories.
     *
     * <p>The category is re-read scoped to the tenant before anything is written, so an id
     * belonging to another college cannot be used to attach an image to it — the upload
     * form supplies that id and a form value is never trusted for scope.
     */
    @Transactional
    public void setOutletImage(Long categoryId, Long tenantId, MultipartFile file) {
        Category category = categoryDao.findByIdAndTenantId(categoryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        String path = fileStorageService.storeCategoryImage(tenantId, file);
        categoryImageDao.upsert(CategoryImage.builder()
                .categoryId(category.getId())
                .tenantId(category.getTenantId())
                .outletId(category.getOutletId())
                .imagePath(path)
                .build());
    }

    /** Removes an outlet's own image, dropping that category back to the platform default. */
    @Transactional
    public void clearOutletImage(Long categoryId, Long tenantId) {
        categoryImageDao.delete(categoryId, tenantId);
    }

    public Optional<CategoryImage> findOutletImage(Long categoryId, Long tenantId) {
        return categoryImageDao.findByCategoryId(categoryId, tenantId);
    }

    // ── An admin setting the platform default ─────────────────────────────────

    /**
     * Stores the platform default for a category name. Admin console only.
     *
     * <p>Tenant-less by design: this is the image every outlet using that name falls back
     * to. {@code displayName} is kept as typed purely so the admin list reads naturally.
     */
    @Transactional
    public void setDefaultImage(String categoryName, Long adminUserId, MultipartFile file) {
        String path = fileStorageService.storeCategoryImage(null, file);
        categoryImageDao.upsertDefault(CategoryDefaultImage.builder()
                .nameKey(normalise(categoryName))
                .displayName(categoryName.trim())
                .imagePath(path)
                .uploadedBy(adminUserId)
                .build());
        invalidateDefaults();
    }

    @Transactional
    public void clearDefaultImage(String categoryName) {
        categoryImageDao.deleteDefault(normalise(categoryName));
        invalidateDefaults();
    }

    /** Every default that exists, for the admin's management list. */
    public List<CategoryDefaultImage> listDefaults() {
        return categoryImageDao.findAllDefaults();
    }

    /** Category names in use somewhere that no admin has illustrated yet. */
    public List<CategoryImageDao.UnillustratedCategory> listCategoriesNeedingImage() {
        return categoryImageDao.findCategoriesWithoutDefault();
    }

    /** Drops the cached defaults so the next menu render reloads them. */
    private void invalidateDefaults() {
        defaults.set(null);
    }
}
