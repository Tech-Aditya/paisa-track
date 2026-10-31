package com.paisatrack.modules.people.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.paisatrack.platform.validation.NullOrNotBlank;

/** Create (name required) or partial update (omitted fields unchanged; empty string clears a contact field). */
public record PersonRequest(
        @NullOrNotBlank @Size(max = 80) String name,
        @Size(max = 20) @Pattern(regexp = "^$|^[0-9+()\\-\\s]{3,20}$", message = "must be a phone number") String phone,
        @Size(max = 254) @Email String email,
        @Size(max = 500) String notes) {
}
