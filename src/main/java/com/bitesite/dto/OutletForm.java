package com.bitesite.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

// Serializable: this form gets flash-attributed across a redirect, which Spring Session
// JDBC persists as a serialized blob rather than keeping in process memory.
@Data
public class OutletForm implements Serializable {
    @NotBlank(message = "Canteen name is required")
    private String name;
}
