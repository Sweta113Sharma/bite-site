package com.bitesite.service;

import com.bitesite.dao.MenuItemDao;
import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.model.MenuItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuItemDao menuItemDao;
    private final AuditService auditService;

    public List<MenuItem> listForOutlet(Long outletId, Long tenantId) {
        return menuItemDao.findByOutletId(outletId, tenantId);
    }

    public List<MenuItem> listAvailableForOutlet(Long outletId, Long tenantId) {
        return menuItemDao.findAvailableByOutletId(outletId, tenantId);
    }

    public MenuItem get(Long id, Long tenantId) {
        return menuItemDao.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
    }

    public MenuItem create(Long tenantId, Long outletId, String name, String category, BigDecimal price,
            BigDecimal discountPrice, BigDecimal discountPercent, Long actorUserId) {
        MenuItem item = MenuItem.builder()
                .tenantId(tenantId)
                .outletId(outletId)
                .name(name)
                .category(category)
                .price(price)
                .discountPrice(discountPrice)
                .discountPercent(discountPercent)
                .available(true)
                .build();
        MenuItem saved = menuItemDao.save(item);
        auditService.record(actorUserId, tenantId, "MenuItem", saved.getId(), "CREATE", null, saved);
        return saved;
    }

    public MenuItem update(Long id, Long tenantId, String name, String category, BigDecimal price,
            BigDecimal discountPrice, BigDecimal discountPercent, Long actorUserId) {
        MenuItem before = get(id, tenantId);
        MenuItem updated = MenuItem.builder()
                .id(id)
                .tenantId(tenantId)
                .outletId(before.getOutletId())
                .name(name)
                .category(category)
                .price(price)
                .discountPrice(discountPrice)
                .discountPercent(discountPercent)
                .available(before.isAvailable())
                .build();
        MenuItem saved = menuItemDao.save(updated);
        auditService.record(actorUserId, tenantId, "MenuItem", id, "UPDATE", before, saved);
        return saved;
    }

    public void setAvailability(Long id, Long tenantId, boolean available, Long actorUserId) {
        MenuItem before = get(id, tenantId);
        menuItemDao.updateAvailability(id, tenantId, available);
        auditService.record(actorUserId, tenantId, "MenuItem", id,
                available ? "MARK_AVAILABLE" : "MARK_UNAVAILABLE", before.isAvailable(), available);
    }

    public void delete(Long id, Long tenantId, Long actorUserId) {
        MenuItem before = get(id, tenantId);
        menuItemDao.delete(id, tenantId);
        auditService.record(actorUserId, tenantId, "MenuItem", id, "DELETE", before, null);
    }
}
