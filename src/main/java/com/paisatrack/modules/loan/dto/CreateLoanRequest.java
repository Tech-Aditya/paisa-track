package com.paisatrack.modules.loan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.loan.LoanType;
import com.paisatrack.platform.validation.CurrencyCode;

public record CreateLoanRequest(
        @NotBlank @Size(max = 80) @Schema(example = "HDFC Home Loan") String name,
        @Size(max = 100) @Schema(example = "HDFC Bank") String lender,
        @NotNull LoanType type,
        @NotNull @Schema(example = "2500000") BigDecimal principal,
        @Schema(description = "Defaults to the disbursement account's currency, else your preferred currency") @CurrencyCode String currency,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Schema(description = "Yearly interest rate in percent", example = "8.5") BigDecimal annualRate,
        @NotNull @Min(1) @Max(600) @Schema(example = "240") Integer tenureMonths,
        @Schema(description = "Your bank's EMI; calculated with the standard formula when omitted") BigDecimal emiAmount,
        @Schema(description = "Disbursement date; defaults to today") LocalDate startDate,
        @Schema(description = "Defaults to one month after startDate") LocalDate firstEmiDate,
        @Schema(description = "If the money was credited to you, that account's balance goes up (never income)") UUID disbursementAccountId,
        @Schema(description = "Account EMIs are usually paid from; defaults to your default account") UUID paymentAccountId,
        @Schema(description = "EXPENSE category for interest; defaults to Financial Charges > Interest") UUID interestCategoryId,
        @Min(0) @Max(600) @Schema(description = "EMIs already paid before you started tracking; not posted to your accounts") Integer emisPaidBeforeTracking,
        @Size(max = 1000) String notes) {
}
