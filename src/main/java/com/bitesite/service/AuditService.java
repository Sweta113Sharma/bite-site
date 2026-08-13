package com.bitesite.service;

import com.bitesite.dao.AuditLogDao;
import com.bitesite.model.AuditLogEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Records who changed what, before/after, for anything financially or operationally
 * sensitive: menu prices/discounts, order status, tenant/user management. A JSON
 * serialization failure here must never break the calling business operation, so it's
 * logged and swallowed rather than propagated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogDao auditLogDao;
    private final ObjectMapper objectMapper;

    public void record(Long actorUserId, Long tenantId, String entityType, Long entityId, String action,
            Object before, Object after) {
        AuditLogEntry entry = AuditLogEntry.builder()
                .actorUserId(actorUserId)
                .tenantId(tenantId)
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .build();
        auditLogDao.save(entry);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit payload for {}", value.getClass(), e);
            return null;
        }
    }
}
