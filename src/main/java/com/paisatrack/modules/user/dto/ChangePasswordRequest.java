package com.paisatrack.modules.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.paisatrack.platform.validation.Password;

public record ChangePasswordRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotNull @Password String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[***]";
    }
}
