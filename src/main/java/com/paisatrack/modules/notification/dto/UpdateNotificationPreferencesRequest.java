package com.paisatrack.modules.notification.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.paisatrack.modules.notification.NotificationType;

/** Only the types listed change; within an item, null fields keep their current value. */
public record UpdateNotificationPreferencesRequest(@NotEmpty List<@Valid Item> preferences) {

    public record Item(@NotNull NotificationType type, Boolean enabled, @Min(0) @Max(30) Integer daysBefore) {
    }
}
