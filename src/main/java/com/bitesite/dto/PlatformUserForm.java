package com.bitesite.dto;

import com.bitesite.model.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

// Serializable: flash-attributed across a redirect on a validation error, which Spring
// Session JDBC persists as a serialized blob rather than keeping in process memory.
@Data
public class PlatformUserForm implements Serializable {

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email address")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotNull(message = "Select a role")
    private Role role;
}
