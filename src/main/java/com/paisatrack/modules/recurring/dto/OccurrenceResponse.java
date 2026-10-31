package com.paisatrack.modules.recurring.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.modules.recurring.RecurringKind;
import com.paisatrack.modules.recurring.RecurringType;

/** One due date of a rule. Only CONFIRMED ones are real transactions. */
public record OccurrenceResponse(
        UUID id,
        UUID ruleId,
        String ruleName,
        RecurringKind kind,
        RecurringType type,
        String currency,
        LocalDate dueDate,
        BigDecimal expectedAmount,
        Status status,
        @Schema(description = "Set once confirmed") BigDecimal actualAmount,
        UUID transactionId,
        Instant resolvedAt,
        String note) {

    public enum Status {
        /** Expected, due later. */
        UPCOMING,
        /** Expected, due today. */
        DUE,
        /** Expected, due date passed; stays until confirmed or skipped. */
        OVERDUE,
        CONFIRMED,
        SKIPPED
    }
}
