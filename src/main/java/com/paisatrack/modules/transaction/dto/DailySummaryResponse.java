package com.paisatrack.modules.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** One day's income and spending, per currency (currencies are never mixed). */
public record DailySummaryResponse(LocalDate date, String basis, List<CurrencySummary> currencies) {

    public record CurrencySummary(
            String currency,
            BigDecimal totalIncome,
            BigDecimal totalExpense,
            BigDecimal netCashFlow,
            long incomeCount,
            long expenseCount,
            BigDecimal averageExpense,
            HighestExpense highestExpense,
            List<CategoryTotal> expenseByCategory) {
    }

    public record HighestExpense(UUID transactionId, BigDecimal amount, String description, String categoryName) {
    }

    /** Totals roll subcategories up into their top-level category. */
    public record CategoryTotal(UUID categoryId, String name, BigDecimal total, long count) {
    }
}
