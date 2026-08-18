package com.bitesite.dao;

import com.bitesite.model.MenuItem;

import java.util.List;
import java.util.Optional;

public interface MenuItemDao {
    Optional<MenuItem> findByIdAndTenantId(Long id, Long tenantId);

    List<MenuItem> findByOutletId(Long outletId, Long tenantId);

    List<MenuItem> findAvailableByOutletId(Long outletId, Long tenantId);

    MenuItem save(MenuItem item);

    void updateAvailability(Long id, Long tenantId, boolean available);

    void delete(Long id, Long tenantId);
}
