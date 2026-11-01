package com.paisatrack.modules.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.modules.account.AccountType;
import com.paisatrack.modules.budget.dto.BudgetResponse;

/**
 * One report for a day, week, month, year or custom range, in one currency. Figures come from
 * recorded transactions only (never expected or planned ones) and keep every kind of money
 * movement apart: income, spending (of which loan interest), transfers, investments, loan
 * principal, lending and borrowing.
 */
public record ReportResponse(
        Kind kind,
        LocalDate from,
        LocalDate to,
        String currency,
        @Schema(description = "Today in your timezone; figures run up to the earlier of this and 'to'") LocalDate asOf,
        @Schema(description = "False while the period is still running") boolean complete,
        String basis,
        @Schema(description = "Custom reports only") Filters filters,
        Summary summary,
        Comparison comparison,
        List<CategoryLine> expenseByCategory,
        List<CategoryLine> incomeByCategory,
        Granularity granularity,
        @Schema(description = "Per day (ranges up to 62 days) or per month, zero-filled up to asOf") List<SeriesPoint> series,
        @Schema(description = "Day (or month, for yearly and long ranges) with the most spending") Peak highestSpending,
        @Schema(description = "Day (or month) with the least spending, zero included") Peak lowestSpending,
        List<TopExpense> topExpenses,
        List<AccountLine> accounts,
        @Schema(description = "Budgets in effect on the last day of the report; null for filtered reports") List<BudgetLine> budgets,
        @Schema(description = "Null when the report is filtered") Salary salary,
        @Schema(description = "Null when the report is filtered") Lending lending,
        @Schema(description = "Null when the report is filtered") Loans loans,
        List<Insight> insights,
        @Schema(description = "Other currencies converted into the report currency, with the rates used and what had no rate")
        List<ConversionLine> conversions,
        @Schema(description = "Entries left out because no exchange rate was available") long unconvertedEntries) {

    public enum Kind { DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM }

    public enum Granularity { DAY, MONTH }

    public record Filters(UUID accountId, UUID categoryId, String type) {
    }

    public record Summary(
            BigDecimal income,
            long incomeCount,
            @Schema(description = "All expenses, loan interest included (as on the dashboard and budgets)") BigDecimal expense,
            long expenseCount,
            @Schema(description = "Expenses without loan interest") BigDecimal consumption,
            @Schema(description = "Loan interest, part of expense") BigDecimal interest,
            @Schema(description = "income - expense") BigDecimal netCashFlow,
            @Schema(description = "netCashFlow / income, %; null without income") BigDecimal savingsRate,
            @Schema(description = "expense / number of expenses") BigDecimal averageExpense,
            @Schema(description = "expense / days elapsed") BigDecimal averageDailyExpense,
            @Schema(description = "Between your own accounts; never income or spending") BigDecimal transfers,
            long transferCount,
            @Schema(description = "Transfers that paid a credit card; the purchases were already counted") BigDecimal cardBillPayments,
            @Schema(description = "Transfers into investment accounts") BigDecimal invested,
            @Schema(description = "Transfers out of investment accounts") BigDecimal withdrawn,
            BigDecimal netInvested,
            @Schema(description = "Loan principal repaid: reduces the loan, not spending") BigDecimal loanPrincipalRepaid,
            @Schema(description = "Loan money received: not income") BigDecimal loanDisbursed) {
    }

    public record Comparison(
            LocalDate from,
            LocalDate to,
            @Schema(description = "SAME_POINT: the same number of days into the previous period; FULL_PERIOD otherwise") String basis,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal consumption,
            BigDecimal netCashFlow,
            BigDecimal savingsRate,
            @Schema(description = "Change vs the previous period, %; null when it had none") BigDecimal incomeChange,
            BigDecimal expenseChange) {
    }

    public record CategoryLine(UUID categoryId, String name, BigDecimal total, long count,
                               @Schema(description = "Share of the total, %") BigDecimal share,
                               BigDecimal previousTotal,
                               @Schema(description = "Change vs the comparison period, %; null when it had none") BigDecimal change) {
    }

    public record SeriesPoint(LocalDate date, BigDecimal income, BigDecimal expense, BigDecimal net) {
    }

    public record Peak(LocalDate date, BigDecimal amount) {
    }

    public record TopExpense(UUID id, @Schema(description = "In the report currency") BigDecimal amount, LocalDate date,
                             String description, String merchant, String category,
                             @Schema(description = "As recorded") BigDecimal originalAmount, String originalCurrency) {
    }

    /** One other currency: how many entries were converted, with which rates, and how many had no rate. */
    public record ConversionLine(String currency, long convertedEntries, long missingEntries,
                                 @Schema(description = "Earliest and latest rate dates used") LocalDate firstRateDate, LocalDate lastRateDate,
                                 List<String> sources,
                                 @Schema(description = "Earliest date with no rate available") LocalDate missingFrom) {
    }

    public record AccountLine(UUID id, String name, AccountType type,
                              @Schema(description = "The account's own currency; account figures are never converted") String currency,
                              BigDecimal inflow, BigDecimal outflow, BigDecimal net,
                              @Schema(description = "Balance now (for credit cards: amount owed)") BigDecimal currentBalance) {
    }

    public record BudgetLine(UUID id, String name, BigDecimal amount, BigDecimal spent, BigDecimal remaining,
                             BigDecimal percentUsed, BudgetResponse.Status status, LocalDate periodStart, LocalDate periodEnd) {
    }

    public record Salary(BigDecimal received, BigDecimal spent,
                         @Schema(description = "spent / received, %; null when no salary was received") BigDecimal utilization) {
    }

    public record Lending(BigDecimal lent, BigDecimal repaidToYou, BigDecimal owedToYouNow,
                          BigDecimal borrowed, BigDecimal youRepaid, BigDecimal youOweNow) {
    }

    public record Loans(BigDecimal principalRepaid, BigDecimal interestPaid, BigDecimal outstandingNow, BigDecimal monthlyEmi,
                        @Schema(description = "EMIs missed or due in the next 30 days") BigDecimal emisDueSoon) {
    }

    /** A fact-based observation. Only stated when the numbers behind it exist. */
    public record Insight(String code, Tone tone, String message) {
        public enum Tone { POSITIVE, NEUTRAL, WARNING }
    }
}
