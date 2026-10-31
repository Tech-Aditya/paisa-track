package com.paisatrack.modules.debt.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Who owes what, per currency. Tracking only: none of this is in account balances or net worth. */
public record DebtSummaryResponse(String basis, List<CurrencyTotals> currencies) {

    public record CurrencyTotals(
            String currency,
            @Schema(description = "Outstanding on money you lent") BigDecimal owedToYou,
            @Schema(description = "Outstanding on money you borrowed") BigDecimal youOwe,
            BigDecimal overdueOwedToYou,
            BigDecimal overdueYouOwe,
            int openLent,
            int openBorrowed,
            int overdueCount) {
    }
}
