package com.paisatrack.modules.account.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Totals per currency. Different currencies are never added together. */
public record AccountsSummaryResponse(List<CurrencyTotals> currencies) {

    public record CurrencyTotals(
            String currency,
            int accountCount,
            BigDecimal totalAssets,
            @Schema(description = "Amounts owed on credit cards") BigDecimal totalLiabilities,
            @Schema(description = "Outstanding principal of active loans") BigDecimal loanOutstanding,
            @Schema(description = "Assets - card balances - loan principal") BigDecimal netWorth) {
    }
}
