package com.bitesite.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class GrievanceForm implements Serializable {

    @NotBlank(message = "Subject is required")
    private String subject;

    @NotBlank(message = "Please describe the issue")
    private String message;
}
