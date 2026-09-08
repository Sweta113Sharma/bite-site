package com.bitesite.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * The grievance officer edit form.
 *
 * <p>Every field except designation is required here, so the console cannot save a
 * half-filled officer at all. That is stricter than the page needs, because the page also
 * refuses to publish an incomplete one, but a save that silently produced a still-unpublished
 * officer would look like it had worked.
 *
 * <p>Sizes match platform_settings.config_value (VARCHAR(500)); the address gets the room
 * because it is the one field that is realistically multi-line.
 */
@Data
public class GrievanceOfficerForm implements Serializable {

    @NotBlank(message = "A real person's name is required")
    @Size(max = 200, message = "Name is too long")
    private String name;

    @Size(max = 200, message = "Designation is too long")
    private String designation;

    @NotBlank(message = "A contact email is required")
    @Email(message = "That does not look like an email address")
    @Size(max = 320, message = "Email is too long")
    private String email;

    @NotBlank(message = "A postal contact address is required")
    @Size(max = 500, message = "Address is too long")
    private String address;

    @NotBlank(message = "State the response timeframe you can actually meet")
    @Size(max = 200, message = "Response window is too long")
    private String responseWindow;
}
