package com.paisatrack.modules.user.dto;

import jakarta.validation.constraints.Size;

import com.paisatrack.platform.validation.NullOrNotBlank;

/** Partial update: omitted fields are left unchanged. */
public record UpdateProfileRequest(
        @NullOrNotBlank @Size(max = 100) String fullName) {
}
