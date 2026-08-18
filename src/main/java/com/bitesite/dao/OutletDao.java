package com.bitesite.dao;

import com.bitesite.model.Outlet;

import java.util.List;
import java.util.Optional;

public interface OutletDao {
    Optional<Outlet> findById(Long id);

    Optional<Outlet> findByIdAndTenantId(Long id, Long tenantId);

    List<Outlet> findByTenantId(Long tenantId);

    List<Outlet> findActiveByTenantId(Long tenantId);

    Outlet save(Outlet outlet);
}
