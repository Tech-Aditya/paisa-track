package com.paisatrack.modules.user.dto;

import java.time.DayOfWeek;

import com.paisatrack.modules.user.Theme;
import com.paisatrack.platform.validation.CurrencyCode;
import com.paisatrack.platform.validation.TimeZoneId;

/** Partial update: omitted fields are left unchanged. */
public record UpdatePreferencesRequest(
        @CurrencyCode String defaultCurrency,
        @TimeZoneId String timezone,
        DayOfWeek weekStartDay,
        Theme theme) {
}
