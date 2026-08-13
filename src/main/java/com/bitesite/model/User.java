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
public class User {
    private Long id;
    private Long tenantId;
    private Long outletId;
    private String name;
    private String email;
    private String passwordHash;
    private String phone;
    private String rollNo;
    private Role role;
    private boolean active;
    private LocalDateTime createdAt;
}
