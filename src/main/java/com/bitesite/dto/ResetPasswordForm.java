package com.bitesite.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serializable;

// Serializable: flash-attributed across a redirect on a validation error, which Spring
// Session JDBC persists as a serialized blob rather than keeping in process memory.
@Data
public class ResetPasswordForm implements Serializable {

    /**
     * Only filled in when this page was opened cold — someone who was sent a code by an
     * admin, or who is finishing on a different device from the one that asked. When the
     * session already knows which account is resetting, this stays empty and is ignored.
     */
    private String email;

    @NotBlank(message = "Enter the code we emailed you")
    @Pattern(regexp = "\\d{6}", message = "The code is 6 digits")
    private String code;

    @StrongPassword
    private String newPassword;

    @NotBlank(message = "Confirm your new password")
    private String confirmPassword;

    public boolean confirmationMatches() {
        return newPassword != null && newPassword.equals(confirmPassword);
    }
}
