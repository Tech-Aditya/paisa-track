package com.paisatrack.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.paisatrack.transaction.PaymentMethod;
import com.paisatrack.validation.CurrencyCode;

/** Money actually received. Expected income (e.g. next salary) is not recorded here. */
public record IncomeRequest(
        @NotNull @Schema(example = "50000.00") BigDecimal amount,
        @NotNull UUID categoryId,
        @Schema(description = "Account the money went into. Defaults to your default account on create; required on update")
        UUID accountId,
        @CurrencyCode String currency,
        @Schema(description = "Defaults to today in your timezone on create; required on update") LocalDate transactionDate,
        LocalTime transactionTime,
        @Size(max = 200) String description,
        @Schema(description = "Who paid, e.g. employer or client") @Size(max = 100) String payer,
        PaymentMethod paymentMethod,
        @Size(max = 1000) String notes) {
}
