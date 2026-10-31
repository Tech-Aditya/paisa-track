package com.paisatrack.modules.recurring.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.recurring.Frequency;
import com.paisatrack.modules.recurring.RecurringKind;
import com.paisatrack.modules.recurring.RecurringType;
import com.paisatrack.modules.transaction.PaymentMethod;
import com.paisatrack.platform.validation.CurrencyCode;

public record CreateRecurringRequest(
        @NotBlank @Size(max = 80) @Schema(example = "House rent") String name,
        @NotNull RecurringKind kind,
        @NotNull @Schema(description = "What confirming an occurrence records") RecurringType type,
        @NotNull @Schema(description = "Expected amount; each confirmation can use the actual amount", example = "18000") BigDecimal amount,
        @Schema(description = "Must equal the account's currency; defaults to it") @CurrencyCode String currency,
        @Schema(description = "Paid from (expense, transfer) or received into (income). Defaults to your default account") UUID accountId,
        @Schema(description = "Transfers only: the destination account, e.g. an investment account") UUID toAccountId,
        @Schema(description = "Required for expense and income") UUID categoryId,
        PaymentMethod paymentMethod,
        @Size(max = 100) @Schema(description = "Merchant or payer") String counterparty,
        @Size(max = 200) String description,
        @Size(max = 1000) String notes,
        @NotNull Frequency frequency,
        @Min(1) @Max(99) @Schema(description = "Every N periods, e.g. 2 with WEEKLY = fortnightly. Default 1") Integer interval,
        @Schema(description = "First due date; anchors the schedule. Defaults to today") LocalDate startDate,
        @Schema(description = "Last possible due date; open-ended when omitted") LocalDate endDate) {
}
