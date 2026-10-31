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
import com.paisatrack.modules.debt.dto.DebtResponse;
import com.paisatrack.modules.loan.dto.LoanSummaryResponse;
import com.paisatrack.modules.recurring.dto.OccurrenceResponse;
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
        List<TransactionResponse> recentTransactions,
        @Schema(description = "Lending and borrowing in your default currency; not part of net worth") DebtsOverview debts,
        @Schema(description = "Loans in your default currency; outstanding principal is already in net worth") LoansOverview loans,
        @Schema(description = "Recurring bills and income still expected (plans, not in any total)") RecurringOverview recurring) {

    public record Periods(Period today, Period week, Period month,
                          @Schema(description = "The from/to range, when one was requested") Period selected) {
    }

    public record Period(LocalDate from, LocalDate to, BigDecimal income, BigDecimal expense, BigDecimal netCashFlow,
                         long incomeCount, long expenseCount) {
    }

    public record AccountsOverview(BigDecimal totalAssets, BigDecimal totalLiabilities,
                                   @Schema(description = "Outstanding loan principal") BigDecimal loanOutstanding, BigDecimal netWorth,
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

    public record LoansOverview(BigDecimal outstandingPrincipal, BigDecimal monthlyEmi, int activeLoans, int missedCount,
                                @Schema(description = "Missed or due within 30 days, soonest first") List<LoanSummaryResponse.UpcomingEmi> upcoming) {
    }

    public record RecurringOverview(
            @Schema(description = "Expected expenses and transfers out: overdue plus the next 30 days") BigDecimal expectedOutflow,
            @Schema(description = "Expected income: overdue plus the next 30 days") BigDecimal expectedInflow,
            int overdueCount,
            @Schema(description = "Overdue or due within 30 days, soonest first") List<OccurrenceResponse> upcoming) {
    }

    public record DebtsOverview(BigDecimal owedToYou, BigDecimal youOwe, int overdueCount,
                                @Schema(description = "Overdue or due within 14 days, soonest first") List<DebtResponse> dueSoon) {
    }

    public record MonthlyTrend(@Schema(type = "string", example = "2026-10") YearMonth month,
                               BigDecimal income, BigDecimal expense, BigDecimal netCashFlow) {
    }
}
