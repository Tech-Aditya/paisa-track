package com.paisatrack.modules.auth.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.modules.user.dto.UserProfileResponse;

public record AuthResponse(
        @Schema(example = "Bearer") String tokenType,
        String accessToken,
        @Schema(description = "Access token lifetime in seconds") long expiresIn,
        Instant accessTokenExpiresAt,
        @Schema(description = "Single-use; exchange at /api/v1/auth/refresh for a new pair") String refreshToken,
        Instant refreshTokenExpiresAt,
        UserProfileResponse user) {

    @Override
    public String toString() {
        return "AuthResponse[userId=" + (user == null ? null : user.id()) + ", tokens=***]";
    }
}
