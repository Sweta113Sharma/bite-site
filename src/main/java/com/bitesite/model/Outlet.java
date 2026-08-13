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
public class Outlet {
    private Long id;
    private Long tenantId;
    private String name;
    private boolean active;
    private LocalDateTime createdAt;
}
