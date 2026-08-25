package com.bitesite.service;

import com.bitesite.dao.MenuItemDao;
import com.bitesite.dao.OrderDao;
import com.bitesite.dto.MenuItemForm;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.MenuItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuItemDao menuItemDao;
    private final OrderDao orderDao;
    private final AuditService auditService;
    private final FileStorageService fileStorageService;

    public List<MenuItem> listForOutlet(Long outletId, Long tenantId) {
        return withTodayCounts(menuItemDao.findByOutletId(outletId, tenantId), tenantId, outletId);
    }

    public List<MenuItem> listAvailableForOutlet(Long outletId, Long tenantId) {
        return withTodayCounts(menuItemDao.findAvailableByOutletId(outletId, tenantId), tenantId, outletId);
    }

    /**
     * Fills in {@link MenuItem#getSoldToday()} for a whole outlet's list in one query
     * rather than one per item. Items with no cap get the count too — it costs nothing and
     * gives the outlet's own menu screen a "sold today" figure for every line.
     */
    private List<MenuItem> withTodayCounts(List<MenuItem> items, Long tenantId, Long outletId) {
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, Integer> sold = soldToday(tenantId, outletId);
        items.forEach(item -> item.setSoldToday(sold.getOrDefault(item.getId(), 0)));
        return items;
    }

    /** menuItemId → quantity ordered at this outlet since midnight. The day boundary is
     * resolved by the database, not here — see {@link OrderDao#sumQuantitiesByMenuItemToday}. */
    public Map<Long, Integer> soldToday(Long tenantId, Long outletId) {
        return orderDao.sumQuantitiesByMenuItemToday(tenantId, outletId);
    }

    public MenuItem get(Long id, Long tenantId) {
        return menuItemDao.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
    }

    /** Single item with its day's count attached, for the screens that show one item. */
    public MenuItem getWithTodayCount(Long id, Long tenantId) {
        MenuItem item = get(id, tenantId);
        item.setSoldToday(soldToday(tenantId, item.getOutletId()).getOrDefault(id, 0));
        return item;
    }

    public MenuItem create(Long tenantId, Long outletId, MenuItemForm form, MultipartFile photo, Long actorUserId) {
        MenuItem item = MenuItem.builder()
                .tenantId(tenantId)
                .outletId(outletId)
                .name(form.getName())
                .category(form.getCategory())
                .photoPath(storeIfProvided(tenantId, photo))
                .price(form.getPrice())
                .discountPrice(form.getDiscountPrice())
                .discountPercent(form.getDiscountPercent())
                .dailyLimit(form.getDailyLimit())
                .available(true)
                .build();
        MenuItem saved = menuItemDao.save(item);
        auditService.record(actorUserId, tenantId, "MenuItem", saved.getId(), "CREATE", null, saved);
        return saved;
    }

    public MenuItem update(Long id, Long tenantId, MenuItemForm form, MultipartFile photo, Long actorUserId) {
        MenuItem before = get(id, tenantId);
        MenuItem updated = MenuItem.builder()
                .id(id)
                .tenantId(tenantId)
                .outletId(before.getOutletId())
                .name(form.getName())
                .category(form.getCategory())
                .photoPath(resolvePhotoPath(tenantId, form, photo, before))
                .price(form.getPrice())
                .discountPrice(form.getDiscountPrice())
                .discountPercent(form.getDiscountPercent())
                .dailyLimit(form.getDailyLimit())
                .available(before.isAvailable())
                .build();
        MenuItem saved = menuItemDao.save(updated);
        auditService.record(actorUserId, tenantId, "MenuItem", id, "UPDATE", before, saved);
        return saved;
    }

    /**
     * A new photo replaces the old one; ticking "remove photo" drops back to the generated
     * illustration; leaving both alone keeps whatever the item already had — an empty file
     * input is "I didn't touch this", not "delete it".
     */
    private String resolvePhotoPath(Long tenantId, MenuItemForm form, MultipartFile photo, MenuItem before) {
        if (photo != null && !photo.isEmpty()) {
            return storeIfProvided(tenantId, photo);
        }
        return form.isRemovePhoto() ? null : before.getPhotoPath();
    }

    private String storeIfProvided(Long tenantId, MultipartFile photo) {
        return photo != null && !photo.isEmpty() ? fileStorageService.storeMenuItemPhoto(tenantId, photo) : null;
    }

    public void setAvailability(Long id, Long tenantId, boolean available, Long actorUserId) {
        MenuItem before = get(id, tenantId);
        menuItemDao.updateAvailability(id, tenantId, available);
        auditService.record(actorUserId, tenantId, "MenuItem", id,
                available ? "MARK_AVAILABLE" : "MARK_UNAVAILABLE", before.isAvailable(), available);
    }

    /**
     * Puts every out-of-stock item at an outlet back on sale. Yesterday's sold-out items
     * are the normal state of a canteen menu at opening time, and switching a dozen of them
     * back on one at a time is the kind of chore that ends with staff not bothering to mark
     * anything out of stock at all.
     */
    public int markAllAvailable(Long outletId, Long tenantId, Long actorUserId) {
        int restored = menuItemDao.markAllAvailable(outletId, tenantId);
        if (restored > 0) {
            auditService.record(actorUserId, tenantId, "Outlet", outletId, "MENU_MARK_ALL_AVAILABLE",
                    null, restored + " items");
        }
        return restored;
    }

    public void delete(Long id, Long tenantId, Long actorUserId) {
        MenuItem before = get(id, tenantId);
        menuItemDao.delete(id, tenantId);
        auditService.record(actorUserId, tenantId, "MenuItem", id, "DELETE", before, null);
    }
}
