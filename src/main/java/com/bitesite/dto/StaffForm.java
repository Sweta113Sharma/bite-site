package com.bitesite.dto;

import com.bitesite.model.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

// Serializable: this form gets flash-attributed across a redirect, which Spring Session
// JDBC persists as a serialized blob rather than keeping in process memory.
@Data
public class StaffForm implements Serializable {

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email address")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotNull(message = "Select a canteen")
    private Long outletId;

    /** Manager or operator. Validated against {@code Role.isOutletPortalRole()} in the
     * controller as well as here, so this field cannot become a route to a platform role. */
    @NotNull(message = "Select a role")
    private Role role;
}
