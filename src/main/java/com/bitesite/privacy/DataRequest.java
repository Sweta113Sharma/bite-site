package com.bitesite.privacy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A data-principal request: the queue behind the "your rights" section of the policy. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataRequest {

    public enum Kind { ACCESS, CORRECTION, ERASURE }

    public enum Status { OPEN, IN_PROGRESS, RESOLVED, REJECTED }

    private Long id;
    private Long userId;
    private Long tenantId;
    private Kind kind;
    private Status status;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;

    /** Filled in for the admin queue so a request is not just a pair of ids. */
    private String userName;
    private String userEmail;
}
