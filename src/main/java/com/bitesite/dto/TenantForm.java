package com.bitesite.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class TenantForm implements Serializable {
    @NotBlank(message = "College name is required")
    private String name;
}
