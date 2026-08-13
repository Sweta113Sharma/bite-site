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
public class OnboardingLead {
    private Long id;
    private Long tenantId;
    private String collegeName;
    private String contactName;
    private String contactEmail;
    private String contactPhone;
    private OnboardingStage stage;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
