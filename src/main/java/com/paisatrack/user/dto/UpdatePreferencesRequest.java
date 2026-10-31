package com.paisatrack.user.dto;

import java.time.DayOfWeek;

import com.paisatrack.user.Theme;
import com.paisatrack.validation.CurrencyCode;
import com.paisatrack.validation.TimeZoneId;

/** Partial update: omitted fields are left unchanged. */
public record UpdatePreferencesRequest(
        @CurrencyCode String defaultCurrency,
        @TimeZoneId String timezone,
        DayOfWeek weekStartDay,
        Theme theme) {
}
