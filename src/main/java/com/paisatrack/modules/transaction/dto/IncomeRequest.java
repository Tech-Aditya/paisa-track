package com.paisatrack.modules.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.transaction.PaymentMethod;
import com.paisatrack.platform.validation.CurrencyCode;

/** Money actually received. Expected income (e.g. next salary) is configured on income sources, not here. */
public record IncomeRequest(
        @NotNull @Schema(example = "50000.00") BigDecimal amount,
        @Schema(description = "Required unless incomeSourceId is given (then defaults to the source's category)") UUID categoryId,
        @Schema(description = "Account the money went into. Defaults to the source's account, else your default account; required on update")
        UUID accountId,
        @CurrencyCode String currency,
        @Schema(description = "Defaults to today in your timezone on create; required on update") LocalDate transactionDate,
        LocalTime transactionTime,
        @Size(max = 200) String description,
        @Schema(description = "Who paid, e.g. employer or client") @Size(max = 100) String payer,
        PaymentMethod paymentMethod,
        @Size(max = 1000) String notes,
        @Schema(description = "Income source this receipt settles, e.g. your salary") UUID incomeSourceId,
        @Schema(description = "Pay period it settles (YYYY-MM); defaults to the month of transactionDate. Needs incomeSourceId",
                type = "string", example = "2026-10")
        YearMonth periodMonth) {
}
