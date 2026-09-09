package com.bitesite.tenant;

import java.util.List;
import java.util.Optional;

public interface TenantDao {
    Optional<Tenant> findById(Long id);

    List<Tenant> findAll();

    /** Colleges selectable at student self-registration and safe to display publicly. */
    List<Tenant> findActive();

    Tenant save(Tenant tenant);

    /** Kept off {@link #save} so a rename can never blank a status or a logo it wasn't given. */
    void updateName(Long id, String name);

    void updateLogoPath(Long id, String logoPath);

    void updateStatus(Long id, TenantStatus status);

    /** Orders ever placed at this college. Non-zero refuses deletion. */
    int countOrders(Long tenantId);

    /** Student accounts on this college. Non-zero refuses deletion. */
    int countStudents(Long tenantId);

    /** The outlet staff accounts that go with the college when it is deleted. */
    List<Long> findStaffUserIds(Long tenantId);

    /** Removes the college and everything that exists only to serve it. Callers must
     *  have refused the operation first when orders or students exist. */
    void delete(Long id);
}
