package com.paisatrack.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.paisatrack.validation.CurrencyCode;

/**
 * Money moved between two of your own accounts. Paying a credit card bill is a transfer from the
 * bank account to the card, so the card purchases are not counted twice.
 */
public record TransferRequest(
        @NotNull BigDecimal amount,
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @Schema(description = "Both accounts must use this currency; defaults to it") @CurrencyCode String currency,
        @Schema(description = "Defaults to today in your timezone on create; required on update") LocalDate transactionDate,
        LocalTime transactionTime,
        @Size(max = 200) String description,
        @Size(max = 1000) String notes) {
}
