package com.paisatrack.account.dto;

import java.math.BigDecimal;
import java.util.List;

/** Totals per currency. Different currencies are never added together. */
public record AccountsSummaryResponse(List<CurrencyTotals> currencies) {

    public record CurrencyTotals(
            String currency,
            int accountCount,
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal netWorth) {
    }
}
