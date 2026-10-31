package com.paisatrack.modules.user.dto;

import java.time.Instant;
import java.util.UUID;

import com.paisatrack.modules.user.User;
import com.paisatrack.modules.user.UserPreferences;

public record UserProfileResponse(
        UUID id,
        String email,
        String fullName,
        Instant createdAt,
        Instant lastLoginAt,
        PreferencesResponse preferences) {

    public static UserProfileResponse from(User user, UserPreferences preferences) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                PreferencesResponse.from(preferences));
    }
}
