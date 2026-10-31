package com.paisatrack.modules.loan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.paisatrack.platform.validation.CurrencyCode;

/** EMI calculator input. Nothing is saved. */
public record LoanPreviewRequest(
        @NotNull BigDecimal principal,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal annualRate,
        @NotNull @Min(1) @Max(600) Integer tenureMonths,
        BigDecimal emiAmount,
        LocalDate firstEmiDate,
        @CurrencyCode String currency) {
}
