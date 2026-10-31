package com.paisatrack.modules.debt.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.transaction.PaymentMethod;
import com.paisatrack.platform.validation.CurrencyCode;

/** Give personId, or personName to use (or create) a contact by name. */
public record CreateDebtRequest(
        UUID personId,
        @Size(max = 80) @Schema(example = "Rahul") String personName,
        @NotNull @Schema(example = "10000") BigDecimal amount,
        @Schema(description = "Defaults to your preferred currency") @CurrencyCode String currency,
        @Schema(description = "When the money changed hands; defaults to today") LocalDate startDate,
        @Schema(description = "When it should be repaid (optional)") LocalDate dueDate,
        @Schema(description = "When to remind you (kept for the notifications phase)") LocalDate remindOn,
        @Size(max = 200) String description,
        @Size(max = 1000) String notes,
        PaymentMethod paymentMethod) {
}
