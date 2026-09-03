package com.bitesite.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

// Serializable: flash-attributed across a redirect on a validation error, which Spring
// Session JDBC persists as a serialized blob rather than keeping in process memory.
@Data
public class ProfileForm implements Serializable {

    @NotBlank(message = "Name is required")
    @Size(max = 150, message = "Name is too long")
    private String name;

    // Same rule as registration: optional, but a well-formed 10-digit Indian mobile number
    // when supplied, since it is what gets handed to Twilio.
    @Pattern(regexp = "^$|^[6-9]\\d{9}$", message = "Enter a valid 10-digit mobile number")
    private String phone;

    @Size(max = 50, message = "Roll number is too long")
    private String rollNo;
}
