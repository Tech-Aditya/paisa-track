package com.paisatrack.modules.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.account.AccountService;
import com.paisatrack.modules.account.dto.AccountResponse;
import com.paisatrack.modules.budget.BudgetService;
import com.paisatrack.modules.debt.DebtService;
import com.paisatrack.modules.debt.dto.DebtSummaryResponse;
import com.paisatrack.platform.money.MoneyRules;
import com.paisatrack.modules.dashboard.dto.DashboardResponse;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.AccountBalance;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.AccountsOverview;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.CategorySpending;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.DailyAmount;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.MonthlyTrend;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.Period;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.Periods;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.modules.income.IncomeSourceService;
import com.paisatrack.modules.transaction.TransactionQueryService;
import com.paisatrack.modules.transaction.TransactionTotalsService;
import com.paisatrack.modules.transaction.TransactionTotalsService.PeriodTotals;
import com.paisatrack.modules.transaction.dto.TransactionFilter;
import com.paisatrack.modules.transaction.dto.TransactionResponse;
import com.paisatrack.modules.user.UserPreferencesService;
import com.paisatrack.modules.user.dto.PreferencesResponse;

/**
 * Read-only home-screen aggregates. Every figure comes from the same transaction totals that
 * /transactions and the daily summary use, so they always reconcile. Day, week and month
 * boundaries follow the user's timezone and week-start preference.
 */
@Service
public class DashboardService {

    static final String BASIS = "Recorded income and expenses in your default currency. Transfers, voided entries "
            + "and expected (not yet received) income are excluded.";
    static final int MAX_RANGE_DAYS = 366;
    private static final int TREND_MONTHS = 6;

    private final TransactionTotalsService totals;
    private final TransactionQueryService transactions;
    private final AccountService accounts;
    private final BudgetService budgets;
    private final IncomeSourceService incomeSources;
    private final DebtService debts;
    private final UserPreferencesService preferences;

    DashboardService(TransactionTotalsService totals, TransactionQueryService transactions, AccountService accounts,
                     BudgetService budgets, IncomeSourceService incomeSources, DebtService debts,
                     UserPreferencesService preferences) {
        this.totals = totals;
        this.transactions = transactions;
        this.accounts = accounts;
        this.budgets = budgets;
        this.incomeSources = incomeSources;
        this.debts = debts;
        this.preferences = preferences;
    }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(UUID userId, LocalDate from, LocalDate to) {
        PreferencesResponse prefs = preferences.get(userId);
        String currency = prefs.defaultCurrency();
        LocalDate today = preferences.today(userId);
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(prefs.weekStartDay()));
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.with(TemporalAdjusters.lastDayOfMonth());

        boolean rangeRequested = from != null || to != null;
        LocalDate rangeFrom = rangeRequested ? requireRange(from, to)[0] : monthStart;
        LocalDate rangeTo = rangeRequested ? to : monthEnd;

        PeriodTotals monthTotals = totals.totals(userId, currency, monthStart, monthEnd);
        Period month = period(monthStart, monthEnd, monthTotals, currency);
        Periods periods = new Periods(
                period(userId, currency, today, today),
                period(userId, currency, weekStart, weekStart.plusDays(6)),
                month,
                rangeRequested ? period(userId, currency, rangeFrom, rangeTo) : null);

        BigDecimal savingsRate = monthTotals.income().signum() == 0 ? null
                : month.netCashFlow().multiply(BigDecimal.valueOf(100)).divide(monthTotals.income(), 2, RoundingMode.HALF_UP);

        return new DashboardResponse(today, currency, BASIS, periods, savingsRate, monthTotals.otherCurrencyCount(),
                accountsOverview(userId, currency),
                budgets.list(userId, today, false),
                incomeSources.monthOverview(userId, YearMonth.from(today)),
                categorySpending(userId, currency, rangeFrom, rangeTo),
                dailySpending(userId, currency, rangeFrom, rangeTo),
                monthlyTrend(userId, currency, YearMonth.from(today)),
                recent(userId, 10),
                debtsOverview(userId, currency));
    }

    /** Spending by top-level category with each one's share (default: this month). */
    @Transactional(readOnly = true)
    public List<CategorySpending> categorySpending(UUID userId, LocalDate from, LocalDate to) {
        LocalDate today = preferences.today(userId);
        boolean rangeRequested = from != null || to != null;
        LocalDate rangeFrom = rangeRequested ? requireRange(from, to)[0] : today.withDayOfMonth(1);
        LocalDate rangeTo = rangeRequested ? to : today.with(TemporalAdjusters.lastDayOfMonth());
        return categorySpending(userId, preferences.defaultCurrency(userId), rangeFrom, rangeTo);
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> recent(UUID userId, int limit) {
        TransactionFilter filter = new TransactionFilter(null, null, null, null, null, null, null, null,
                null, null, false, 0, limit, null, null);
        return transactions.list(userId, filter).items();
    }

    // ---- pieces ----

    private DashboardResponse.DebtsOverview debtsOverview(UUID userId, String currency) {
        DebtSummaryResponse.CurrencyTotals totals = debts.summary(userId).currencies().stream()
                .filter(t -> t.currency().equals(currency)).findFirst().orElse(null);
        var dueSoon = debts.reminders(userId, 14).stream().filter(d -> d.currency().equals(currency)).limit(5).toList();
        return new DashboardResponse.DebtsOverview(
                MoneyRules.scaled(totals == null ? BigDecimal.ZERO : totals.owedToYou(), currency),
                MoneyRules.scaled(totals == null ? BigDecimal.ZERO : totals.youOwe(), currency),
                totals == null ? 0 : totals.overdueCount(), dueSoon);
    }

    private Period period(UUID userId, String currency, LocalDate from, LocalDate to) {
        return period(from, to, totals.totals(userId, currency, from, to), currency);
    }

    private static Period period(LocalDate from, LocalDate to, PeriodTotals t, String currency) {
        return new Period(from, to, MoneyRules.scaled(t.income(), currency), MoneyRules.scaled(t.expense(), currency),
                MoneyRules.scaled(t.income().subtract(t.expense()), currency), t.incomeCount(), t.expenseCount());
    }

    private AccountsOverview accountsOverview(UUID userId, String currency) {
        List<AccountResponse> list = accounts.list(userId, false);
        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal liabilities = BigDecimal.ZERO;
        for (var c : accounts.summary(userId).currencies()) {
            if (c.currency().equals(currency)) {
                assets = c.totalAssets();
                liabilities = c.totalLiabilities();
            }
        }
        List<AccountBalance> balances = list.stream()
                .map(a -> new AccountBalance(a.id(), a.name(), a.type(), a.nature(), a.currency(), a.currentBalance()))
                .toList();
        return new AccountsOverview(MoneyRules.scaled(assets, currency), MoneyRules.scaled(liabilities, currency),
                MoneyRules.scaled(assets.subtract(liabilities), currency), balances);
    }

    private List<CategorySpending> categorySpending(UUID userId, String currency, LocalDate from, LocalDate to) {
        var rows = totals.expenseByCategory(userId, currency, from, to);
        BigDecimal total = rows.stream().map(TransactionTotalsService.CategorySpend::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        return rows.stream().map(r -> new CategorySpending(r.categoryId(), r.name(), MoneyRules.scaled(r.total(), currency),
                r.count(), total.signum() == 0 ? BigDecimal.ZERO
                        : r.total().multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP))).toList();
    }

    private List<DailyAmount> dailySpending(UUID userId, String currency, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> byDay = totals.dailyExpenses(userId, currency, from, to).stream()
                .collect(Collectors.toMap(TransactionTotalsService.DayAmount::date, TransactionTotalsService.DayAmount::amount));
        List<DailyAmount> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(new DailyAmount(d, MoneyRules.scaled(byDay.getOrDefault(d, BigDecimal.ZERO), currency)));
        }
        return days;
    }

    private List<MonthlyTrend> monthlyTrend(UUID userId, String currency, YearMonth current) {
        YearMonth first = current.minusMonths(TREND_MONTHS - 1);
        Map<LocalDate, TransactionTotalsService.MonthTotals> byMonth = totals
                .monthlyTotals(userId, currency, first.atDay(1), current.atEndOfMonth()).stream()
                .collect(Collectors.toMap(TransactionTotalsService.MonthTotals::month, m -> m));
        List<MonthlyTrend> trend = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(current); m = m.plusMonths(1)) {
            var t = byMonth.get(m.atDay(1));
            BigDecimal income = t == null ? BigDecimal.ZERO : t.income();
            BigDecimal expense = t == null ? BigDecimal.ZERO : t.expense();
            trend.add(new MonthlyTrend(m, MoneyRules.scaled(income, currency), MoneyRules.scaled(expense, currency),
                    MoneyRules.scaled(income.subtract(expense), currency)));
        }
        return trend;
    }

    private static LocalDate[] requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new FieldValidationException(from == null ? "from" : "to", "is required when selecting a range");
        }
        if (from.isAfter(to)) {
            throw new FieldValidationException("from", "must not be after 'to'");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new FieldValidationException("to", "range can be at most " + MAX_RANGE_DAYS + " days");
        }
        return new LocalDate[] {from, to};
    }
}
