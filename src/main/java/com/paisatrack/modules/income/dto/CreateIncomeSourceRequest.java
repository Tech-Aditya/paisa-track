package com.paisatrack.modules.income.dto;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.income.IncomeSourceType;
import com.paisatrack.platform.validation.CurrencyCode;

public record CreateIncomeSourceRequest(
        @NotBlank @Size(max = 60) @Schema(example = "Acme Corp salary") String name,
        @NotNull IncomeSourceType type,
        @Schema(description = "INCOME category; defaults to the seeded one for the type") UUID categoryId,
        @Schema(description = "Account it is usually credited to; defaults to your default account when recording") UUID accountId,
        @Schema(description = "Defaults to the account's currency, else your preferred currency") @CurrencyCode String currency,
        @Schema(description = "Expected monthly amount; omit for irregular income") BigDecimal expectedAmount,
        @Schema(description = "Expected credit day of month (1-31, capped to month length); default 1") @Min(1) @Max(31) Integer expectedDay,
        @Schema(description = "First month to expect it, e.g. 2026-11; defaults to this month", type = "string", example = "2026-11")
        YearMonth startMonth,
        @Size(max = 500) String notes) {
}
