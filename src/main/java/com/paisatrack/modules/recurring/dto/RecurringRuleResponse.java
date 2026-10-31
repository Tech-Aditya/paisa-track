package com.paisatrack.modules.recurring.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.modules.recurring.Frequency;
import com.paisatrack.modules.recurring.RecurringKind;
import com.paisatrack.modules.recurring.RecurringType;
import com.paisatrack.modules.recurring.RuleStatus;
import com.paisatrack.modules.transaction.PaymentMethod;

public record RecurringRuleResponse(
        UUID id,
        String name,
        RecurringKind kind,
        RecurringType type,
        BigDecimal amount,
        String currency,
        Ref account,
        Ref toAccount,
        Ref category,
        PaymentMethod paymentMethod,
        String counterparty,
        String description,
        String notes,
        Frequency frequency,
        int interval,
        LocalDate startDate,
        LocalDate endDate,
        RuleStatus status,
        @Schema(description = "Earliest occurrence still expected (overdue first)") OccurrenceResponse nextOccurrence,
        @Schema(description = "Expected occurrences whose due date has passed") int overdueCount,
        Instant createdAt,
        Instant updatedAt) {

    public record Ref(UUID id, String name) {
    }
}
