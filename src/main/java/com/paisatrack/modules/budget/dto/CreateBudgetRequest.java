package com.paisatrack.modules.budget.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.budget.BudgetPeriod;
import com.paisatrack.modules.budget.BudgetScope;
import com.paisatrack.platform.validation.CurrencyCode;

public record CreateBudgetRequest(
        @Schema(description = "Defaults to e.g. 'Food & Dining (monthly)'") @Size(max = 60) String name,
        @NotNull BudgetScope scope,
        @Schema(description = "Required for CATEGORY scope; an EXPENSE category") UUID categoryId,
        @NotNull BudgetPeriod period,
        @NotNull @Schema(example = "5000") BigDecimal amount,
        @Schema(description = "Defaults to your preferred currency. Only spending in this currency counts") @CurrencyCode String currency,
        @Schema(description = "CUSTOM: range start (required). Others: first day in effect; defaults to the current period's start")
        LocalDate startDate,
        @Schema(description = "CUSTOM: range end (required). Others: optional last day in effect") LocalDate endDate,
        @Schema(description = "Alert percentages, ascending; default [75, 90, 100]") @Size(min = 1, max = 5) List<Integer> alertThresholds) {
}
