package com.bitesite.tenant;

import java.util.List;
import java.util.Optional;

public interface TenantDao {
    Optional<Tenant> findById(Long id);

    List<Tenant> findAll();

    /** Colleges selectable at student self-registration and safe to display publicly. */
    List<Tenant> findActive();

    Tenant save(Tenant tenant);

    void updateLogoPath(Long id, String logoPath);

    void updateStatus(Long id, TenantStatus status);
}
