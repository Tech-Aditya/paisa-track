package com.paisatrack.modules.loan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Loans per currency, plus EMIs that are missed or due soon across all loans. */
public record LoanSummaryResponse(List<CurrencyTotals> currencies, List<UpcomingEmi> upcoming) {

    public record CurrencyTotals(String currency, int activeLoans, BigDecimal outstandingPrincipal, BigDecimal monthlyEmi,
                                 BigDecimal principalPaidThisYear, BigDecimal interestPaidThisYear, int missedCount) {
    }

    public record UpcomingEmi(UUID loanId, String loanName, String lender, String currency, int number,
                              LocalDate dueDate, BigDecimal emiAmount, BigDecimal principalAmount,
                              BigDecimal interestAmount, InstallmentResponse.Status status) {
    }
}
