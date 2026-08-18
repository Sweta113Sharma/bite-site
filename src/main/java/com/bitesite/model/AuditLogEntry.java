package com.bitesite.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEntry {
    private Long id;
    private Long actorUserId;
    private Long tenantId;
    private String entityType;
    private Long entityId;
    private String action;
    private String beforeJson;
    private String afterJson;
    private LocalDateTime createdAt;
}
