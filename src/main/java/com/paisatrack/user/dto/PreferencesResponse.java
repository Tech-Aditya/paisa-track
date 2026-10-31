package com.paisatrack.user.dto;

import java.time.DayOfWeek;
import java.time.Instant;

import com.paisatrack.user.Theme;
import com.paisatrack.user.UserPreferences;

public record PreferencesResponse(
        String defaultCurrency,
        String timezone,
        DayOfWeek weekStartDay,
        Theme theme,
        Instant updatedAt) {

    public static PreferencesResponse from(UserPreferences preferences) {
        return new PreferencesResponse(
                preferences.getDefaultCurrency(),
                preferences.getTimezone(),
                preferences.getWeekStartDay(),
                preferences.getTheme(),
                preferences.getUpdatedAt());
    }
}
