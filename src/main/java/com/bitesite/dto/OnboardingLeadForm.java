package com.bitesite.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class OnboardingLeadForm implements Serializable {

    @NotBlank(message = "College name is required")
    private String collegeName;

    @NotBlank(message = "Contact name is required")
    private String contactName;

    @NotBlank(message = "Contact email is required")
    @Email(message = "Enter a valid email address")
    private String contactEmail;

    private String contactPhone;
    private String notes;
}
