package com.paisatrack.modules.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.account.AccountService;
import com.paisatrack.modules.account.dto.AccountResponse;
import com.paisatrack.modules.budget.BudgetService;
import com.paisatrack.modules.budget.dto.BudgetResponse;
import com.paisatrack.modules.category.CategoryRef;
import com.paisatrack.modules.category.CategoryService;
import com.paisatrack.modules.currency.CurrencyService;
import com.paisatrack.modules.currency.RateBook;
import com.paisatrack.modules.debt.DebtService;
import com.paisatrack.modules.debt.dto.DebtSummaryResponse;
import com.paisatrack.modules.loan.LoanService;
import com.paisatrack.modules.loan.dto.LoanSummaryResponse;
import com.paisatrack.modules.report.ReportQueries.Filter;
import com.paisatrack.modules.report.ReportQueries.CategoryRow;
import com.paisatrack.modules.report.ReportQueries.DayRow;
import com.paisatrack.modules.report.ReportQueries.Totals;
import com.paisatrack.modules.report.ReportQueries.TotalsRow;
import com.paisatrack.modules.report.dto.ReportResponse;
import com.paisatrack.modules.report.dto.ReportResponse.Insight;
import com.paisatrack.modules.report.dto.ReportResponse.Kind;
import com.paisatrack.modules.transaction.TransactionType;
import com.paisatrack.modules.user.UserPreferencesService;
import com.paisatrack.modules.user.dto.PreferencesResponse;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.platform.money.MoneyFormat;
import com.paisatrack.platform.money.MoneyRules;

/**
 * Daily, weekly, monthly, yearly and custom reports. Days, weeks and months follow the user's
 * timezone and week start. A period still running is compared with the same point of the previous
 * period (1-15 Nov vs 1-15 Oct); finished periods are compared in full.
 */
@Service
public class ReportService {

    static final String BASIS = "Recorded transactions only: expected salary, recurring plans and voided entries are excluded. "
            + "Transfers, investments, loan principal, loan disbursements, lending and borrowing are never income or spending; "
            + "loan interest is spending and shown separately. Other currencies are converted with the latest rate on or before "
            + "each entry's date; entries without a rate are listed, not added.";
    static final int TOP_EXPENSES = 5;
    static final int MAX_CUSTOM_DAYS = 731;
    private static final int DAILY_SERIES_MAX_DAYS = 62;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ReportQueries queries;
    private final CategoryService categories;
    private final AccountService accounts;
    private final BudgetService budgets;
    private final DebtService debts;
    private final LoanService loans;
    private final UserPreferencesService preferences;
    private final CurrencyService currencies;

    ReportService(ReportQueries queries, CategoryService categories, AccountService accounts, BudgetService budgets,
                  DebtService debts, LoanService loans, UserPreferencesService preferences, CurrencyService currencies) {
        this.queries = queries;
        this.categories = categories;
        this.accounts = accounts;
        this.budgets = budgets;
        this.debts = debts;
        this.loans = loans;
        this.preferences = preferences;
        this.currencies = currencies;
    }

    @Transactional(readOnly = true)
    public ReportResponse daily(UUID userId, LocalDate date, String currency) {
        LocalDate day = date != null ? date : preferences.today(userId);
        return build(userId, Kind.DAILY, day, day, currency, Filter.NONE, null, "date");
    }

    /** The week containing {@code date}, starting on the user's week-start day. */
    @Transactional(readOnly = true)
    public ReportResponse weekly(UUID userId, LocalDate date, String currency) {
        PreferencesResponse prefs = preferences.get(userId);
        LocalDate day = date != null ? date : preferences.today(userId);
        LocalDate start = weekStart(day, prefs.weekStartDay());
        return build(userId, Kind.WEEKLY, start, start.plusDays(6), currency, Filter.NONE, null, "date");
    }

    @Transactional(readOnly = true)
    public ReportResponse monthly(UUID userId, YearMonth month, String currency) {
        YearMonth m = month != null ? month : YearMonth.from(preferences.today(userId));
        return build(userId, Kind.MONTHLY, m.atDay(1), m.atEndOfMonth(), currency, Filter.NONE, null, "month");
    }

    @Transactional(readOnly = true)
    public ReportResponse yearly(UUID userId, Integer year, String currency) {
        Year y = year != null ? Year.of(year) : Year.from(preferences.today(userId));
        return build(userId, Kind.YEARLY, y.atDay(1), y.atMonth(12).atEndOfMonth(), currency, Filter.NONE, null, "year");
    }

    /** Any range up to two years, optionally narrowed to one account, category (with subcategories) or type. */
    @Transactional(readOnly = true)
    public ReportResponse custom(UUID userId, LocalDate from, LocalDate to, UUID accountId, UUID categoryId,
                                 TransactionType type, String currency) {
        if (from == null) {
            throw new FieldValidationException("from", "is required");
        }
        if (to == null) {
            throw new FieldValidationException("to", "is required");
        }
        if (from.isAfter(to)) {
            throw new FieldValidationException("from", "must not be after 'to'");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_CUSTOM_DAYS) {
            throw new FieldValidationException("to", "range can be at most two years");
        }
        if (accountId != null) {
            accounts.require(userId, accountId);
        }
        Set<UUID> categoryIds = categoryId == null ? null : categories.withSubcategories(userId, categoryId);
        Filter filter = new Filter(accountId, categoryIds, type == null ? null : type.name());
        ReportResponse.Filters filters = filter.isEmpty() ? null
                : new ReportResponse.Filters(accountId, categoryId, type == null ? null : type.name());
        return build(userId, Kind.CUSTOM, from, to, currency, filter, filters, "from");
    }

    static LocalDate weekStart(LocalDate day, DayOfWeek first) {
        return day.with(TemporalAdjusters.previousOrSame(first));
    }

    // ---- periods ----

    /** The report range, how far it has run, and the like-for-like range it is compared with. */
    record Window(LocalDate from, LocalDate to, LocalDate end, boolean complete, LocalDate prevFrom, LocalDate prevTo,
                  boolean samePoint) {

        long days() {
            return ChronoUnit.DAYS.between(from, end) + 1;
        }
    }

    static Window window(Kind kind, LocalDate from, LocalDate to, LocalDate today) {
        LocalDate end = to.isAfter(today) ? today : to;
        boolean complete = to.isBefore(today);
        long elapsed = ChronoUnit.DAYS.between(from, end);
        LocalDate prevFrom;
        LocalDate prevTo;
        switch (kind) {
            case DAILY -> {
                prevFrom = from.minusDays(1);
                prevTo = prevFrom;
            }
            case WEEKLY -> {
                prevFrom = from.minusWeeks(1);
                prevTo = prevFrom.plusDays(elapsed);
            }
            case MONTHLY -> {
                prevFrom = from.minusMonths(1);
                LocalDate prevEnd = from.minusDays(1);
                LocalDate sameDay = prevFrom.plusDays(elapsed);
                prevTo = complete || sameDay.isAfter(prevEnd) ? prevEnd : sameDay;
            }
            case YEARLY -> {
                prevFrom = from.minusYears(1);
                prevTo = complete ? from.minusDays(1) : end.minusYears(1);
            }
            default -> {
                prevTo = from.minusDays(1);
                prevFrom = prevTo.minusDays(elapsed);
            }
        }
        return new Window(from, to, end, complete, prevFrom, prevTo, !complete && kind != Kind.DAILY && kind != Kind.CUSTOM);
    }

    // ---- assembly ----

    private ReportResponse build(UUID userId, Kind kind, LocalDate from, LocalDate to, String requestedCurrency, Filter filter,
                                 ReportResponse.Filters filters, String dateField) {
        LocalDate today = preferences.today(userId);
        if (from.isAfter(today)) {
            throw new FieldValidationException(dateField, "is in the future");
        }
        String c = requestedCurrency != null ? requestedCurrency : preferences.defaultCurrency(userId);
        Window w = window(kind, from, to, today);

        List<TotalsRow> curRows = queries.totals(userId, w.from(), w.end(), filter);
        List<TotalsRow> prevRows = queries.totals(userId, w.prevFrom(), w.prevTo(), filter);
        List<CategoryRow> curExpense = queries.byCategory(userId, w.from(), w.end(), "EXPENSE", filter);
        List<CategoryRow> prevExpense = queries.byCategory(userId, w.prevFrom(), w.prevTo(), "EXPENSE", filter);
        List<CategoryRow> curIncome = queries.byCategory(userId, w.from(), w.end(), "INCOME", filter);
        List<CategoryRow> prevIncome = queries.byCategory(userId, w.prevFrom(), w.prevTo(), "INCOME", filter);
        List<DayRow> days = queries.daily(userId, w.from(), w.end(), filter);
        List<ReportQueries.Expense> top = queries.topExpenses(userId, w.from(), w.end(), TOP_EXPENSES, filter);
        List<ReportQueries.DebtActivity> debtRows = filter.isEmpty() ? queries.debtActivity(userId, w.from(), w.end()) : List.of();
        LoanSummaryResponse loanSummary = filter.isEmpty() ? loans.summary(userId, 30) : null;
        DebtSummaryResponse debtSummary = filter.isEmpty() ? debts.summary(userId) : null;

        Set<String> involved = new HashSet<>();
        Stream.of(curRows, prevRows).flatMap(List::stream).forEach(r -> involved.add(r.currency()));
        Stream.of(curExpense, prevExpense, curIncome, prevIncome).flatMap(List::stream).forEach(r -> involved.add(r.currency()));
        debtRows.forEach(r -> involved.add(r.currency()));
        if (loanSummary != null) {
            loanSummary.currencies().forEach(x -> involved.add(x.currency()));
        }
        if (debtSummary != null) {
            debtSummary.currencies().forEach(x -> involved.add(x.currency()));
        }
        // One rate book for the whole report. Only the current period's totals are recorded as
        // "converted / missing"; breakdowns use the same rates without counting entries twice.
        RateBook book = currencies.book(userId, c, involved, today);
        RateBook quiet = book.untracked();

        Totals cur = sum(curRows, book);
        Totals prev = sum(prevRows, quiet);
        ReportResponse.Summary summary = summary(cur, w.days(), c);
        ReportResponse.Comparison comparison = comparison(cur, prev, w, c);

        Set<UUID> categoryIds = new HashSet<>();
        Stream.of(curExpense, prevExpense, curIncome, prevIncome).flatMap(List::stream).forEach(x -> categoryIds.add(x.categoryId()));
        top.forEach(x -> categoryIds.add(x.categoryId()));
        Map<UUID, CategoryRef> refs = categories.refs(userId, categoryIds);
        List<ReportResponse.CategoryLine> expenseLines = categoryLines(byCategory(curExpense, quiet), byCategory(prevExpense, quiet), refs, c);
        List<ReportResponse.CategoryLine> incomeLines = categoryLines(byCategory(curIncome, quiet), byCategory(prevIncome, quiet), refs, c);

        boolean monthly = ChronoUnit.DAYS.between(from, to) + 1 > DAILY_SERIES_MAX_DAYS;
        List<ReportResponse.SeriesPoint> series = series(days, quiet, w, monthly, c);
        ReportResponse.Peak high = null;
        ReportResponse.Peak low = null;
        if (kind != Kind.DAILY && !series.isEmpty()) {
            high = series.stream().max(Comparator.comparing(ReportResponse.SeriesPoint::expense))
                    .map(p -> new ReportResponse.Peak(p.date(), p.expense())).orElse(null);
            low = series.stream().min(Comparator.comparing(ReportResponse.SeriesPoint::expense))
                    .map(p -> new ReportResponse.Peak(p.date(), p.expense())).orElse(null);
        }

        List<ReportResponse.TopExpense> topExpenses = top.stream()
                .flatMap(e -> quiet.quote(e.currency(), e.date()).map(q -> new ReportResponse.TopExpense(e.id(),
                        MoneyRules.scaled(e.amount().multiply(q.rate()), c), e.date(), e.description(), e.counterparty(),
                        name(refs.get(e.categoryId())), MoneyRules.scaled(e.amount(), e.currency()), e.currency())).stream())
                .sorted(Comparator.comparing(ReportResponse.TopExpense::amount).reversed())
                .limit(TOP_EXPENSES)
                .toList();
        List<ReportResponse.AccountLine> accountLines = accountLines(userId, w, filter);

        List<ReportResponse.BudgetLine> budgetLines = null;
        ReportResponse.Salary salary = null;
        ReportResponse.Lending lending = null;
        ReportResponse.Loans loanSection = null;
        if (filter.isEmpty()) {
            budgetLines = budgetLines(userId, c, w.end());
            salary = new ReportResponse.Salary(MoneyRules.scaled(cur.salary(), c), summary.expense(), percent(cur.expense(), cur.salary()));
            lending = lending(debtRows, debtSummary, quiet, w, today, c);
            loanSection = loans(loanSummary, quiet, today, cur, c);
        }
        List<ReportResponse.ConversionLine> conversions = book.usage().stream()
                .map(u -> new ReportResponse.ConversionLine(u.currency(), u.converted(), u.missing(), u.firstRateDate(), u.lastRateDate(),
                        u.sources().stream().sorted().toList(), u.missingFrom()))
                .toList();
        long unconverted = conversions.stream().mapToLong(ReportResponse.ConversionLine::missingEntries).sum();
        List<Insight> insights = Insights.of(kind, w, summary, comparison, expenseLines, budgetLines, loanSection, conversions, c);
        return new ReportResponse(kind, from, to, c, today, w.complete(), BASIS, filters, summary, comparison, expenseLines,
                incomeLines, monthly ? ReportResponse.Granularity.MONTH : ReportResponse.Granularity.DAY, series, high, low,
                topExpenses, accountLines, budgetLines, salary, lending, loanSection, insights, conversions, unconverted);
    }

    /** Adds up rows of any currency in the report currency; rows without a rate are left out (and recorded). */
    private static Totals sum(List<TotalsRow> rows, RateBook book) {
        BigDecimal[] a = new BigDecimal[11];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        long incomeCount = 0;
        long expenseCount = 0;
        long transferCount = 0;
        for (TotalsRow r : rows) {
            var rate = book.use(r.currency(), r.date(), r.entries());
            if (rate.isEmpty()) {
                continue;
            }
            BigDecimal x = rate.get();
            Totals t = r.totals();
            BigDecimal[] values = {t.income(), t.expense(), t.interest(), t.transfers(), t.cardPayments(), t.invested(),
                t.withdrawn(), t.principalRepaid(), t.disbursed(), t.salary(), BigDecimal.ZERO};
            for (int i = 0; i < values.length; i++) {
                a[i] = a[i].add(values[i].multiply(x));
            }
            incomeCount += t.incomeCount();
            expenseCount += t.expenseCount();
            transferCount += t.transferCount();
        }
        return new Totals(a[0], incomeCount, a[1], expenseCount, a[2], a[3], transferCount, a[4], a[5], a[6], a[7], a[8], a[9]);
    }

    private record CategorySum(UUID categoryId, BigDecimal total, long count) {
    }

    private static List<CategorySum> byCategory(List<CategoryRow> rows, RateBook book) {
        Map<UUID, BigDecimal> totals = new HashMap<>();
        Map<UUID, Long> counts = new HashMap<>();
        for (CategoryRow r : rows) {
            book.quote(r.currency(), r.date()).ifPresent(q -> {
                totals.merge(r.categoryId(), r.total().multiply(q.rate()), BigDecimal::add);
                counts.merge(r.categoryId(), r.count(), Long::sum);
            });
        }
        return totals.entrySet().stream()
                .map(e -> new CategorySum(e.getKey(), e.getValue(), counts.get(e.getKey())))
                .sorted(Comparator.comparing(CategorySum::total).reversed())
                .toList();
    }

    private static ReportResponse.Summary summary(Totals t, long days, String c) {
        BigDecimal net = t.income().subtract(t.expense());
        return new ReportResponse.Summary(
                MoneyRules.scaled(t.income(), c), t.incomeCount(), MoneyRules.scaled(t.expense(), c), t.expenseCount(),
                MoneyRules.scaled(t.expense().subtract(t.interest()), c), MoneyRules.scaled(t.interest(), c),
                MoneyRules.scaled(net, c), percent(net, t.income()),
                t.expenseCount() == 0 ? MoneyRules.scaled(BigDecimal.ZERO, c) : divide(t.expense(), t.expenseCount(), c),
                divide(t.expense(), days, c),
                MoneyRules.scaled(t.transfers(), c), t.transferCount(), MoneyRules.scaled(t.cardPayments(), c),
                MoneyRules.scaled(t.invested(), c), MoneyRules.scaled(t.withdrawn(), c),
                MoneyRules.scaled(t.invested().subtract(t.withdrawn()), c),
                MoneyRules.scaled(t.principalRepaid(), c), MoneyRules.scaled(t.disbursed(), c));
    }

    private static ReportResponse.Comparison comparison(Totals cur, Totals prev, Window w, String c) {
        BigDecimal net = prev.income().subtract(prev.expense());
        return new ReportResponse.Comparison(w.prevFrom(), w.prevTo(), w.samePoint() ? "SAME_POINT" : "FULL_PERIOD",
                MoneyRules.scaled(prev.income(), c), MoneyRules.scaled(prev.expense(), c),
                MoneyRules.scaled(prev.expense().subtract(prev.interest()), c), MoneyRules.scaled(net, c), percent(net, prev.income()),
                change(MoneyRules.scaled(cur.income(), c), MoneyRules.scaled(prev.income(), c)),
                change(MoneyRules.scaled(cur.expense(), c), MoneyRules.scaled(prev.expense(), c)));
    }

    private static List<ReportResponse.CategoryLine> categoryLines(List<CategorySum> cur, List<CategorySum> prev,
                                                                   Map<UUID, CategoryRef> refs, String c) {
        BigDecimal total = cur.stream().map(CategorySum::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<UUID, BigDecimal> before = prev.stream().collect(Collectors.toMap(CategorySum::categoryId, CategorySum::total));
        return cur.stream().map(x -> {
            BigDecimal now = MoneyRules.scaled(x.total(), c);
            BigDecimal previous = MoneyRules.scaled(before.getOrDefault(x.categoryId(), BigDecimal.ZERO), c);
            return new ReportResponse.CategoryLine(x.categoryId(), name(refs.get(x.categoryId())), now,
                    x.count(), percent(x.total(), total), previous, change(now, previous));
        }).toList();
    }

    /** Zero-filled from the start of the report to how far it has run, per day or per month. */
    private static List<ReportResponse.SeriesPoint> series(List<DayRow> rows, RateBook book, Window w, boolean monthly, String c) {
        Map<LocalDate, BigDecimal[]> buckets = new HashMap<>();
        for (DayRow r : rows) {
            book.quote(r.currency(), r.date()).ifPresent(q -> {
                BigDecimal[] b = buckets.computeIfAbsent(monthly ? r.date().withDayOfMonth(1) : r.date(),
                        k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                b[0] = b[0].add(r.income().multiply(q.rate()));
                b[1] = b[1].add(r.expense().multiply(q.rate()));
            });
        }
        List<ReportResponse.SeriesPoint> out = new ArrayList<>();
        LocalDate d = monthly ? w.from().withDayOfMonth(1) : w.from();
        while (!d.isAfter(w.end())) {
            BigDecimal[] b = buckets.getOrDefault(d, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal income = MoneyRules.scaled(b[0], c);
            BigDecimal expense = MoneyRules.scaled(b[1], c);
            out.add(new ReportResponse.SeriesPoint(d, income, expense, income.subtract(expense)));
            d = monthly ? d.plusMonths(1) : d.plusDays(1);
        }
        return out;
    }

    /** Each account in its own currency: balances are never converted here. */
    private List<ReportResponse.AccountLine> accountLines(UUID userId, Window w, Filter filter) {
        Map<UUID, ReportQueries.AccountFlow> flows = queries.accountFlows(userId, w.from(), w.end());
        return accounts.list(userId, true).stream()
                .filter(a -> filter.accountId() == null ? a.active() || flows.containsKey(a.id()) : a.id().equals(filter.accountId()))
                .map(a -> {
                    String ac = a.currency();
                    ReportQueries.AccountFlow f = flows.getOrDefault(a.id(), new ReportQueries.AccountFlow(BigDecimal.ZERO, BigDecimal.ZERO));
                    return new ReportResponse.AccountLine(a.id(), a.name(), a.type(), ac, MoneyRules.scaled(f.inflow(), ac),
                            MoneyRules.scaled(f.outflow(), ac), MoneyRules.scaled(f.inflow().subtract(f.outflow()), ac), a.currentBalance());
                })
                .toList();
    }

    private List<ReportResponse.BudgetLine> budgetLines(UUID userId, String c, LocalDate date) {
        return budgets.list(userId, date, false).stream()
                .filter(b -> b.currency().equals(c) && b.utilization() != null
                        && b.utilization().status() != BudgetResponse.Status.NOT_IN_EFFECT)
                .map(b -> {
                    BudgetResponse.Utilization u = b.utilization();
                    return new ReportResponse.BudgetLine(b.id(), b.name(), b.amount(), u.spent(), u.remaining(), u.percentUsed(),
                            u.status(), u.periodStart(), u.periodEnd());
                })
                .toList();
    }

    /** Activity converted at the end of the period; balances "now" at today's rate. */
    private static ReportResponse.Lending lending(List<ReportQueries.DebtActivity> rows, DebtSummaryResponse now, RateBook book, Window w,
                                                  LocalDate today, String c) {
        BigDecimal[] a = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (ReportQueries.DebtActivity r : rows) {
            book.quote(r.currency(), w.end()).ifPresent(q -> {
                a[0] = a[0].add(r.lent().multiply(q.rate()));
                a[1] = a[1].add(r.repaidToYou().multiply(q.rate()));
                a[3] = a[3].add(r.borrowed().multiply(q.rate()));
                a[4] = a[4].add(r.youRepaid().multiply(q.rate()));
            });
        }
        for (DebtSummaryResponse.CurrencyTotals t : now.currencies()) {
            book.quote(t.currency(), today).ifPresent(q -> {
                a[2] = a[2].add(t.owedToYou().multiply(q.rate()));
                a[5] = a[5].add(t.youOwe().multiply(q.rate()));
            });
        }
        return new ReportResponse.Lending(MoneyRules.scaled(a[0], c), MoneyRules.scaled(a[1], c), MoneyRules.scaled(a[2], c),
                MoneyRules.scaled(a[3], c), MoneyRules.scaled(a[4], c), MoneyRules.scaled(a[5], c));
    }

    private static ReportResponse.Loans loans(LoanSummaryResponse s, RateBook book, LocalDate today, Totals cur, String c) {
        BigDecimal[] a = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (LoanSummaryResponse.CurrencyTotals t : s.currencies()) {
            book.quote(t.currency(), today).ifPresent(q -> {
                a[0] = a[0].add(t.outstandingPrincipal().multiply(q.rate()));
                a[1] = a[1].add(t.monthlyEmi().multiply(q.rate()));
            });
        }
        for (LoanSummaryResponse.UpcomingEmi e : s.upcoming()) {
            book.quote(e.currency(), today).ifPresent(q -> a[2] = a[2].add(e.emiAmount().multiply(q.rate())));
        }
        return new ReportResponse.Loans(MoneyRules.scaled(cur.principalRepaid(), c), MoneyRules.scaled(cur.interest(), c),
                MoneyRules.scaled(a[0], c), MoneyRules.scaled(a[1], c), MoneyRules.scaled(a[2], c));
    }

    // ---- maths ----

    /** part / whole as a percentage with one decimal; null when whole is zero. */
    static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    /** Change from before to now, %; null when before is zero. */
    static BigDecimal change(BigDecimal now, BigDecimal before) {
        return before.signum() == 0 ? null : percent(now.subtract(before), before);
    }

    private static BigDecimal divide(BigDecimal amount, long by, String c) {
        return amount.divide(BigDecimal.valueOf(by), MoneyRules.fractionDigits(c), RoundingMode.HALF_UP);
    }

    private static String name(CategoryRef ref) {
        return ref == null ? null : ref.name();
    }

    static String money(BigDecimal amount, String currency) {
        return MoneyFormat.format(amount, currency);
    }
}
