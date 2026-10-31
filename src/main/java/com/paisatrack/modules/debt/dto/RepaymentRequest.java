package com.paisatrack.modules.debt.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.transaction.PaymentMethod;

/** A full or partial repayment. Can't exceed what is outstanding. */
public record RepaymentRequest(
        @NotNull @Schema(example = "3000") BigDecimal amount,
        @Schema(description = "Defaults to today") LocalDate repaymentDate,
        PaymentMethod paymentMethod,
        @Size(max = 500) String notes) {
}
