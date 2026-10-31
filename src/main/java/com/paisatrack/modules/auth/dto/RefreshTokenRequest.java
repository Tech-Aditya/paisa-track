package com.paisatrack.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshTokenRequest(@NotBlank @Size(max = 512) String refreshToken) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[***]";
    }
}
