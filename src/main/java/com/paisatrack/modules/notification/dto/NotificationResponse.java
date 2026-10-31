package com.paisatrack.modules.notification.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record NotificationResponse(
        UUID id,
        @Schema(example = "EMI") String type,
        @Schema(description = "UPCOMING, DUE, OVERDUE or ALERT") String stage,
        String title,
        String message,
        @Schema(description = "The loan, debt, budget, income source or recurring occurrence it is about") UUID subjectId,
        LocalDate dueDate,
        @Schema(description = "Web app route that shows the item") String link,
        boolean read,
        Instant createdAt,
        Instant readAt) {
}
