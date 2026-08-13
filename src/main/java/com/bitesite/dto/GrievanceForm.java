package com.bitesite.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GrievanceForm {

    @NotBlank(message = "Subject is required")
    private String subject;

    @NotBlank(message = "Please describe the issue")
    private String message;
}
