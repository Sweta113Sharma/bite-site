package com.bitesite.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class GrievanceForm implements Serializable {

    /** Optional: the order this is about. Ownership is checked in GrievanceService. */
    private Long orderId;

    @NotBlank(message = "Subject is required")
    private String subject;

    @NotBlank(message = "Please describe the issue")
    private String message;
}
