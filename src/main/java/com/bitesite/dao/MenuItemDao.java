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

    /** Switches every item at an outlet back on in one statement — the "we restocked" button.
     * Also clears "out of stock today", which is the other way an item is off sale. */
    int markAllAvailable(Long outletId, Long tenantId);

    /** Takes these items off sale until the end of today. Scoped to the outlet, so an id
     * from another canteen's order can never switch off a different canteen's item. */
    int markOutOfStockToday(List<Long> ids, Long outletId, Long tenantId);

    void delete(Long id, Long tenantId);
}
