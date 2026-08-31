package com.bitesite.dao;

import com.bitesite.model.Outlet;

import java.util.List;
import java.util.Optional;

public interface OutletDao {
    Optional<Outlet> findById(Long id);

    Optional<Outlet> findByIdAndTenantId(Long id, Long tenantId);

    List<Outlet> findByTenantId(Long tenantId);

    /**
     * Every outlet on the platform, regardless of tenant.
     *
     * <p>Deliberately unscoped, for the admin console only. A super admin holds no
     * tenantId, so there is nothing to scope by. Callers must gate this on the admin role
     * — the same contract as the two cross-tenant finders on OrderDao and PaymentDao.
     */
    List<Outlet> findAllAcrossTenants();

    List<Outlet> findActiveByTenantId(Long tenantId);

    Outlet save(Outlet outlet);

    void updateAcceptingOrders(Long id, Long tenantId, boolean acceptingOrders);

    /** Opening hours, contact and notice. Deliberately separate from {@link #save}, which
     * carries the two status flags — a manager editing hours must not be able to
     * accidentally reactivate a canteen an admin disabled. */
    void updateSettings(Long id, Long tenantId, java.time.LocalTime opensAt,
            java.time.LocalTime closesAt, String contactPhone, String notice);

    /** Orders ever placed at this outlet. Deleting one with history would orphan financial
     * records, so callers check this first and offer deactivation instead. */
    int countOrders(Long id);

    /** Staff accounts still pointing at this outlet. */
    List<Long> findStaffUserIds(Long id);

    /** Removes the outlet and its menu. Callers must have cleared the two things that
     * reference it — orders (refused) and staff accounts (detached) — first. */
    void delete(Long id, Long tenantId);
}
