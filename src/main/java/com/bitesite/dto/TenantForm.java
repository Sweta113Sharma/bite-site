package com.bitesite.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TenantForm {
    @NotBlank(message = "College name is required")
    private String name;
}
