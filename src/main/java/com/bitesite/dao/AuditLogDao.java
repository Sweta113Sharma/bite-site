package com.bitesite.dao;

import com.bitesite.model.AuditLogEntry;

import java.util.List;

public interface AuditLogDao {
    void save(AuditLogEntry entry);

    List<AuditLogEntry> findByTenantId(Long tenantId, int limit);

    List<AuditLogEntry> findByEntity(String entityType, Long entityId);
}
