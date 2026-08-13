package com.bitesite.dao;

import com.bitesite.model.TechConfigEntry;

import java.util.List;
import java.util.Optional;

public interface TechConfigDao {
    List<TechConfigEntry> findByTenantId(Long tenantId);

    Optional<String> getValue(Long tenantId, String key);

    void upsert(Long tenantId, String key, String value);
}
