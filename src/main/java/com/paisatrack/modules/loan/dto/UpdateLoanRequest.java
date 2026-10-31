package com.paisatrack.modules.loan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.loan.LoanType;
import com.paisatrack.platform.validation.NullOrNotBlank;

/** Partial update. Loan terms (amount, rate, tenure, EMI, first EMI date) only change while no payment exists. */
public record UpdateLoanRequest(
        @NullOrNotBlank @Size(max = 80) String name,
        @Size(max = 100) String lender,
        LoanType type,
        UUID paymentAccountId,
        UUID interestCategoryId,
        @Size(max = 1000) String notes,
        @Schema(description = "Terms: only before any payment") BigDecimal principal,
        @DecimalMin("0") @DecimalMax("100") BigDecimal annualRate,
        @Min(1) @Max(600) Integer tenureMonths,
        BigDecimal emiAmount,
        LocalDate firstEmiDate) {
}
