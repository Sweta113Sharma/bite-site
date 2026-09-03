package com.bitesite.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

// Serializable: flash-attributed across a redirect on a validation error, which Spring
// Session JDBC persists as a serialized blob rather than keeping in process memory.
@Data
public class ChangePasswordForm implements Serializable {

    @NotBlank(message = "Enter your current password")
    private String currentPassword;

    @StrongPassword
    private String newPassword;

    @NotBlank(message = "Confirm your new password")
    private String confirmPassword;

    /** Cross-field check, kept here rather than in the controller so both the change and
     * reset flows ask it the same way. Only meaningful once both fields are present. */
    public boolean confirmationMatches() {
        return newPassword != null && newPassword.equals(confirmPassword);
    }
}
