package com.paisatrack.modules.recurring.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Everything is optional: the rule's amount and account and today's date are used. */
public record ConfirmOccurrenceRequest(
        @Schema(description = "Actual amount, e.g. this month's electricity bill") BigDecimal amount,
        @Schema(description = "When it was actually paid or received; defaults to today") LocalDate transactionDate,
        @Schema(description = "Paid from / received into; defaults to the rule's account") UUID accountId,
        @Size(max = 1000) String notes) {
}
