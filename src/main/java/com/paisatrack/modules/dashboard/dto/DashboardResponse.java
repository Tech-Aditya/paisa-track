package com.paisatrack.modules.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.modules.account.AccountNature;
import com.paisatrack.modules.account.AccountType;
import com.paisatrack.modules.budget.dto.BudgetResponse;
import com.paisatrack.modules.income.dto.ExpectedIncomeResponse;
import com.paisatrack.modules.transaction.dto.TransactionResponse;

/** Everything the home screen needs, in the user's default currency. */
public record DashboardResponse(
        LocalDate today,
        String currency,
        String basis,
        Periods periods,
        @Schema(description = "Month net cash flow / month income, as a percentage; null with no income") BigDecimal savingsRate,
        @Schema(description = "This month's transactions in other currencies, not included in the figures") long otherCurrencyTransactions,
        AccountsOverview accounts,
        List<BudgetResponse> budgets,
        List<ExpectedIncomeResponse> expectedIncome,
        @Schema(description = "Spending by top-level category over the selected range") List<CategorySpending> expenseByCategory,
        @Schema(description = "Spending per day over the selected range, zero-filled") List<DailyAmount> dailySpending,
        @Schema(description = "Last 6 months, oldest first") List<MonthlyTrend> monthlyTrend,
        List<TransactionResponse> recentTransactions) {

    public record Periods(Period today, Period week, Period month,
                          @Schema(description = "The from/to range, when one was requested") Period selected) {
    }

    public record Period(LocalDate from, LocalDate to, BigDecimal income, BigDecimal expense, BigDecimal netCashFlow,
                         long incomeCount, long expenseCount) {
    }

    public record AccountsOverview(BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal netWorth,
                                   List<AccountBalance> accounts) {
    }

    public record AccountBalance(UUID id, String name, AccountType type, AccountNature nature, String currency,
                                 BigDecimal currentBalance) {
    }

    public record CategorySpending(UUID categoryId, String name, BigDecimal total, long count,
                                   @Schema(description = "Share of the range's spending, %") BigDecimal share) {
    }

    public record DailyAmount(LocalDate date, BigDecimal amount) {
    }

    public record MonthlyTrend(@Schema(type = "string", example = "2026-10") YearMonth month,
                               BigDecimal income, BigDecimal expense, BigDecimal netCashFlow) {
    }
}
