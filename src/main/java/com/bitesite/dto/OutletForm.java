package com.bitesite.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OutletForm {
    @NotBlank(message = "Canteen name is required")
    private String name;
}
