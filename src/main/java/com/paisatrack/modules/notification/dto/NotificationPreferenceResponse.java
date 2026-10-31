package com.paisatrack.modules.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record NotificationPreferenceResponse(
        @Schema(example = "BILL") String type,
        boolean enabled,
        @Schema(description = "How many days ahead the UPCOMING reminder fires (0 = only on the due date)") int daysBefore) {
}
