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

/** Only amount and category are needed to record an expense; everything else has a default. */
public record ExpenseRequest(
        @NotNull @Schema(example = "150.00") BigDecimal amount,
        @NotNull UUID categoryId,
        @Schema(description = "Defaults to your default account on create; required on update") UUID accountId,
        @Schema(description = "Must equal the account's currency; defaults to it") @CurrencyCode String currency,
        @Schema(description = "Defaults to today in your timezone on create; required on update") LocalDate transactionDate,
        LocalTime transactionTime,
        @Size(max = 200) String description,
        @Size(max = 100) String merchant,
        @Schema(description = "Defaults from the account type (cash, card, wallet)") PaymentMethod paymentMethod,
        @Size(max = 1000) String notes) {
}
