package com.paisatrack.modules.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.account.AccountService;
import com.paisatrack.modules.account.dto.AccountResponse;
import com.paisatrack.modules.budget.BudgetService;
import com.paisatrack.modules.currency.CurrencyService;
import com.paisatrack.modules.currency.RateBook;
import com.paisatrack.modules.debt.DebtService;
import com.paisatrack.modules.debt.dto.DebtSummaryResponse;
import com.paisatrack.modules.loan.LoanService;
import com.paisatrack.modules.recurring.RecurringService;
import com.paisatrack.modules.recurring.dto.RecurringSummaryResponse;
import com.paisatrack.modules.loan.dto.LoanSummaryResponse;
import com.paisatrack.platform.money.MoneyRules;
import com.paisatrack.modules.dashboard.dto.DashboardResponse;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.AccountBalance;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.AccountsOverview;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.CategorySpending;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.Conversion;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.DailyAmount;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.MonthlyTrend;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.Period;
import com.paisatrack.modules.dashboard.dto.DashboardResponse.Periods;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.modules.income.IncomeSourceService;
import com.paisatrack.modules.transaction.TransactionQueryService;
import com.paisatrack.modules.transaction.TransactionTotalsService;
import com.paisatrack.modules.transaction.TransactionTotalsService.DayCategorySpend;
import com.paisatrack.modules.transaction.TransactionTotalsService.DayTotals;
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

    static final String BASIS = "Recorded income and expenses, in your default currency. Other currencies are converted with the "
            + "latest rate on or before each day; entries without a rate are counted, not added. Transfers, voided entries and "
            + "expected (not yet received) income are excluded.";
    static final int MAX_RANGE_DAYS = 366;
    private static final int TREND_MONTHS = 6;

    private final TransactionTotalsService totals;
    private final TransactionQueryService transactions;
    private final AccountService accounts;
    private final BudgetService budgets;
    private final IncomeSourceService incomeSources;
    private final DebtService debts;
    private final LoanService loans;
    private final RecurringService recurring;
    private final UserPreferencesService preferences;
    private final CurrencyService currencies;

    DashboardService(TransactionTotalsService totals, TransactionQueryService transactions, AccountService accounts,
                     BudgetService budgets, IncomeSourceService incomeSources, DebtService debts, LoanService loans,
                     RecurringService recurring, UserPreferencesService preferences, CurrencyService currencies) {
        this.totals = totals;
        this.transactions = transactions;
        this.accounts = accounts;
        this.budgets = budgets;
        this.incomeSources = incomeSources;
        this.debts = debts;
        this.loans = loans;
        this.recurring = recurring;
        this.preferences = preferences;
        this.currencies = currencies;
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
        YearMonth current = YearMonth.from(today);
        LocalDate trendStart = current.minusMonths(TREND_MONTHS - 1).atDay(1);

        // One pass over the ledger for every window on the screen, then convert each currency-day.
        LocalDate first = min(min(trendStart, weekStart), rangeFrom);
        LocalDate last = max(max(monthEnd, weekStart.plusDays(6)), rangeTo);
        List<DayTotals> days = totals.dayTotals(userId, first, last);
        List<DayCategorySpend> categoryDays = totals.dayCategorySpend(userId, rangeFrom, rangeTo);
        List<AccountResponse> accountList = accounts.list(userId, false);
        var accountSummary = accounts.summary(userId).currencies();
        LoanSummaryResponse loanSummary = loans.summary(userId, 30);
        DebtSummaryResponse debtSummary = debts.summary(userId);
        RecurringSummaryResponse recurringSummary = recurring.summary(userId, 30);

        Set<String> involved = new HashSet<>();
        days.forEach(d -> involved.add(d.currency()));
        categoryDays.forEach(d -> involved.add(d.currency()));
        accountList.forEach(a -> involved.add(a.currency()));
        loanSummary.currencies().forEach(x -> involved.add(x.currency()));
        debtSummary.currencies().forEach(x -> involved.add(x.currency()));
        recurringSummary.currencies().forEach(x -> involved.add(x.currency()));
        RateBook book = currencies.book(userId, currency, involved, today);
        RateBook quiet = book.untracked();

        Period month = period(monthStart, monthEnd, days, book, currency); // this month's conversions are reported
        Periods periods = new Periods(
                period(today, today, days, quiet, currency),
                period(weekStart, weekStart.plusDays(6), days, quiet, currency),
                month,
                rangeRequested ? period(rangeFrom, rangeTo, days, quiet, currency) : null);

        BigDecimal savingsRate = month.income().signum() == 0 ? null
                : month.netCashFlow().multiply(BigDecimal.valueOf(100)).divide(month.income(), 2, RoundingMode.HALF_UP);
        List<Conversion> conversions = book.usage().stream()
                .map(u -> new Conversion(u.currency(), u.converted(), u.missing(), u.lastRateDate(), u.missingFrom())).toList();
        long unconverted = conversions.stream().mapToLong(Conversion::missingEntries).sum();

        return new DashboardResponse(today, currency, BASIS, periods, savingsRate, unconverted, conversions,
                accountsOverview(accountList, accountSummary, quiet, today, currency),
                budgets.list(userId, today, false),
                incomeSources.monthOverview(userId, current),
                categorySpending(userId, categoryDays, quiet, currency),
                dailySpending(days, quiet, rangeFrom, rangeTo, currency),
                monthlyTrend(days, quiet, current, currency),
                recent(userId, 10),
                debtsOverview(userId, debtSummary, quiet, today, currency),
                loansOverview(loanSummary, quiet, today, currency),
                recurringOverview(recurringSummary, quiet, today, currency));
    }

    /** Spending by top-level category with each one's share (default: this month), converted to the default currency. */
    @Transactional(readOnly = true)
    public List<CategorySpending> categorySpending(UUID userId, LocalDate from, LocalDate to) {
        LocalDate today = preferences.today(userId);
        boolean rangeRequested = from != null || to != null;
        LocalDate rangeFrom = rangeRequested ? requireRange(from, to)[0] : today.withDayOfMonth(1);
        LocalDate rangeTo = rangeRequested ? to : today.with(TemporalAdjusters.lastDayOfMonth());
        String currency = preferences.defaultCurrency(userId);
        List<DayCategorySpend> rows = totals.dayCategorySpend(userId, rangeFrom, rangeTo);
        Set<String> involved = rows.stream().map(DayCategorySpend::currency).collect(Collectors.toSet());
        return categorySpending(userId, rows, currencies.book(userId, currency, involved, today), currency);
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> recent(UUID userId, int limit) {
        TransactionFilter filter = new TransactionFilter(null, null, null, null, null, null, null, null,
                null, null, false, 0, limit, null, null);
        return transactions.list(userId, filter).items();
    }

    // ---- pieces ----

    private static Period period(LocalDate from, LocalDate to, List<DayTotals> days, RateBook book, String currency) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        long incomeCount = 0;
        long expenseCount = 0;
        for (DayTotals d : days) {
            if (d.date().isBefore(from) || d.date().isAfter(to)) {
                continue;
            }
            var rate = book.use(d.currency(), d.date(), d.incomeCount() + d.expenseCount());
            if (rate.isEmpty()) {
                continue;
            }
            income = income.add(d.income().multiply(rate.get()));
            expense = expense.add(d.expense().multiply(rate.get()));
            incomeCount += d.incomeCount();
            expenseCount += d.expenseCount();
        }
        BigDecimal in = MoneyRules.scaled(income, currency);
        BigDecimal out = MoneyRules.scaled(expense, currency);
        return new Period(from, to, in, out, in.subtract(out), incomeCount, expenseCount);
    }

    /** Converts an amount in {@code from} at today's rate; zero (and recorded as missing) when there is none. */
    private static BigDecimal now(BigDecimal amount, String from, RateBook book, LocalDate today) {
        return book.quote(from, today).map(q -> amount.multiply(q.rate())).orElse(BigDecimal.ZERO);
    }

    private static DashboardResponse.LoansOverview loansOverview(LoanSummaryResponse summary, RateBook book, LocalDate today, String currency) {
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal emi = BigDecimal.ZERO;
        int active = 0;
        int missed = 0;
        for (LoanSummaryResponse.CurrencyTotals t : summary.currencies()) {
            outstanding = outstanding.add(now(t.outstandingPrincipal(), t.currency(), book, today));
            emi = emi.add(now(t.monthlyEmi(), t.currency(), book, today));
            active += t.activeLoans();
            missed += t.missedCount();
        }
        return new DashboardResponse.LoansOverview(MoneyRules.scaled(outstanding, currency), MoneyRules.scaled(emi, currency),
                active, missed, summary.upcoming().stream().limit(5).toList());
    }

    private static DashboardResponse.RecurringOverview recurringOverview(RecurringSummaryResponse summary, RateBook book, LocalDate today,
                                                                         String currency) {
        BigDecimal out = BigDecimal.ZERO;
        BigDecimal in = BigDecimal.ZERO;
        int overdue = 0;
        for (RecurringSummaryResponse.CurrencyTotals t : summary.currencies()) {
            out = out.add(now(t.expectedOutflow(), t.currency(), book, today));
            in = in.add(now(t.expectedInflow(), t.currency(), book, today));
            overdue += t.overdueCount();
        }
        return new DashboardResponse.RecurringOverview(MoneyRules.scaled(out, currency), MoneyRules.scaled(in, currency), overdue,
                summary.expected().stream().limit(5).toList());
    }

    private DashboardResponse.DebtsOverview debtsOverview(UUID userId, DebtSummaryResponse summary, RateBook book, LocalDate today,
                                                          String currency) {
        BigDecimal owed = BigDecimal.ZERO;
        BigDecimal owe = BigDecimal.ZERO;
        int overdue = 0;
        for (DebtSummaryResponse.CurrencyTotals t : summary.currencies()) {
            owed = owed.add(now(t.owedToYou(), t.currency(), book, today));
            owe = owe.add(now(t.youOwe(), t.currency(), book, today));
            overdue += t.overdueCount();
        }
        return new DashboardResponse.DebtsOverview(MoneyRules.scaled(owed, currency), MoneyRules.scaled(owe, currency), overdue,
                debts.reminders(userId, 14).stream().limit(5).toList());
    }

    /** Net worth in the default currency: each currency's assets, card debt and loans at today's rate. */
    private static AccountsOverview accountsOverview(List<AccountResponse> list,
                                                     List<com.paisatrack.modules.account.dto.AccountsSummaryResponse.CurrencyTotals> perCurrency,
                                                     RateBook book, LocalDate today, String currency) {
        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal liabilities = BigDecimal.ZERO;
        BigDecimal loanOutstanding = BigDecimal.ZERO;
        List<String> unconverted = new ArrayList<>();
        for (var c : perCurrency) {
            var q = book.quote(c.currency(), today);
            if (q.isEmpty()) {
                unconverted.add(c.currency());
                continue;
            }
            assets = assets.add(c.totalAssets().multiply(q.get().rate()));
            liabilities = liabilities.add(c.totalLiabilities().multiply(q.get().rate()));
            loanOutstanding = loanOutstanding.add(c.loanOutstanding().multiply(q.get().rate()));
        }
        List<AccountBalance> balances = list.stream()
                .map(a -> new AccountBalance(a.id(), a.name(), a.type(), a.nature(), a.currency(), a.currentBalance(),
                        a.currency().equals(currency) ? null
                                : book.quote(a.currency(), today).map(q -> MoneyRules.scaled(a.currentBalance().multiply(q.rate()), currency))
                                        .orElse(null)))
                .toList();
        return new AccountsOverview(MoneyRules.scaled(assets, currency), MoneyRules.scaled(liabilities, currency),
                MoneyRules.scaled(loanOutstanding, currency), MoneyRules.scaled(assets.subtract(liabilities).subtract(loanOutstanding), currency),
                balances, unconverted);
    }

    private List<CategorySpending> categorySpending(UUID userId, List<DayCategorySpend> rows, RateBook book, String currency) {
        Map<UUID, BigDecimal> sums = new HashMap<>();
        Map<UUID, Long> counts = new HashMap<>();
        for (DayCategorySpend r : rows) {
            book.quote(r.currency(), r.date()).ifPresent(q -> {
                sums.merge(r.categoryId(), r.total().multiply(q.rate()), BigDecimal::add);
                counts.merge(r.categoryId(), r.count(), Long::sum);
            });
        }
        Map<UUID, String> names = totals.categoryNames(userId, sums.keySet());
        BigDecimal total = sums.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sums.entrySet().stream()
                .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed())
                .map(e -> new CategorySpending(e.getKey(), names.get(e.getKey()), MoneyRules.scaled(e.getValue(), currency), counts.get(e.getKey()),
                        total.signum() == 0 ? BigDecimal.ZERO : e.getValue().multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP)))
                .toList();
    }

    private static List<DailyAmount> dailySpending(List<DayTotals> rows, RateBook book, LocalDate from, LocalDate to, String currency) {
        Map<LocalDate, BigDecimal> byDay = new HashMap<>();
        for (DayTotals d : rows) {
            if (!d.date().isBefore(from) && !d.date().isAfter(to)) {
                book.quote(d.currency(), d.date()).ifPresent(q -> byDay.merge(d.date(), d.expense().multiply(q.rate()), BigDecimal::add));
            }
        }
        List<DailyAmount> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(new DailyAmount(d, MoneyRules.scaled(byDay.getOrDefault(d, BigDecimal.ZERO), currency)));
        }
        return days;
    }

    private static List<MonthlyTrend> monthlyTrend(List<DayTotals> rows, RateBook book, YearMonth current, String currency) {
        YearMonth first = current.minusMonths(TREND_MONTHS - 1);
        Map<YearMonth, BigDecimal[]> byMonth = new HashMap<>();
        for (DayTotals d : rows) {
            YearMonth m = YearMonth.from(d.date());
            if (m.isBefore(first) || m.isAfter(current)) {
                continue;
            }
            book.quote(d.currency(), d.date()).ifPresent(q -> {
                BigDecimal[] t = byMonth.computeIfAbsent(m, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                t[0] = t[0].add(d.income().multiply(q.rate()));
                t[1] = t[1].add(d.expense().multiply(q.rate()));
            });
        }
        List<MonthlyTrend> trend = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(current); m = m.plusMonths(1)) {
            BigDecimal[] t = byMonth.getOrDefault(m, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal income = MoneyRules.scaled(t[0], currency);
            BigDecimal expense = MoneyRules.scaled(t[1], currency);
            trend.add(new MonthlyTrend(m, income, expense, income.subtract(expense)));
        }
        return trend;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
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
