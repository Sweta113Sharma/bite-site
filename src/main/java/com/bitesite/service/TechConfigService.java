package com.bitesite.service;

import com.bitesite.dao.TechConfigDao;
import com.bitesite.model.TechConfigEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TechConfigService {

    private final TechConfigDao techConfigDao;
    private final AuditService auditService;

    public List<TechConfigEntry> listForTenant(Long tenantId) {
        return techConfigDao.findByTenantId(tenantId);
    }

    public void set(Long tenantId, String key, String value, Long actorUserId) {
        techConfigDao.upsert(tenantId, key, value);
        auditService.record(actorUserId, tenantId, "TechConfig", null, "SET_" + key, null, value);
    }
}
