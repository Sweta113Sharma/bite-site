package com.bitesite.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class StudentRegistrationForm implements Serializable {

    @NotNull(message = "Select your college")
    private Long tenantId;

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email address")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(regexp = ".*[A-Za-z].*", message = "Password must include at least one letter")
    @Pattern(regexp = ".*\\d.*", message = "Password must include at least one number")
    private String password;

    // Optional (phone OTP is only issued when both a number is given and SMS is
    // configured — see UserService.registerStudent), but must be a well-formed 10-digit
    // Indian mobile number when one is provided, since it's what gets sent to Twilio.
    @Pattern(regexp = "^$|^[6-9]\\d{9}$", message = "Enter a valid 10-digit mobile number")
    private String phone;

    private String rollNo;
}
