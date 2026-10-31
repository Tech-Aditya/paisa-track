package com.paisatrack.modules.debt.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.transaction.PaymentMethod;

/** Partial update; omitted fields stay as they are. Changes are audited. */
public record UpdateDebtRequest(
        UUID personId,
        @Schema(description = "Corrects the original amount; can't go below what was already repaid") BigDecimal amount,
        LocalDate startDate,
        LocalDate dueDate,
        @Schema(description = "true removes the due date") Boolean clearDueDate,
        LocalDate remindOn,
        @Size(max = 200) String description,
        @Size(max = 1000) String notes,
        PaymentMethod paymentMethod) {
}
