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
public class Grievance {
    private Long id;
    private Long tenantId;
    private Long raisedByUserId;
    /** The order this ticket is about; null for tickets that aren't order-specific. */
    private Long orderId;
    private String subject;
    private String message;
    private GrievanceStatus status;
    private String adminResponse;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
