package com.bitesite.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class EmailChangeForm implements Serializable {

    @NotBlank(message = "Enter the new email address")
    @Email(message = "Enter a valid email address")
    private String newEmail;

    @NotBlank(message = "Enter your current password")
    private String currentPassword;
}
