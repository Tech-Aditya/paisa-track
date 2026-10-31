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
import com.paisatrack.modules.debt.DebtService;
import com.paisatrack.modules.debt.dto.DebtSummaryResponse;
import com.paisatrack.modules.loan.LoanService;
import com.paisatrack.modules.loan.dto.LoanSummaryResponse;
import com.paisatrack.modules.report.ReportQueries.Filter;
import com.paisatrack.modules.report.ReportQueries.Totals;
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
            + "loan interest is spending and shown separately. One currency; other currencies are counted, not added.";
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

    ReportService(ReportQueries queries, CategoryService categories, AccountService accounts, BudgetService budgets,
                  DebtService debts, LoanService loans, UserPreferencesService preferences) {
        this.queries = queries;
        this.categories = categories;
        this.accounts = accounts;
        this.budgets = budgets;
        this.debts = debts;
        this.loans = loans;
        this.preferences = preferences;
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

        Totals cur = queries.totals(userId, c, w.from(), w.end(), filter);
        Totals prev = queries.totals(userId, c, w.prevFrom(), w.prevTo(), filter);
        ReportResponse.Summary summary = summary(cur, w.days(), c);
        ReportResponse.Comparison comparison = comparison(cur, prev, w, c);

        List<ReportQueries.CategoryTotal> curExpense = queries.byCategory(userId, c, w.from(), w.end(), "EXPENSE", filter);
        List<ReportQueries.CategoryTotal> prevExpense = queries.byCategory(userId, c, w.prevFrom(), w.prevTo(), "EXPENSE", filter);
        List<ReportQueries.CategoryTotal> curIncome = queries.byCategory(userId, c, w.from(), w.end(), "INCOME", filter);
        List<ReportQueries.CategoryTotal> prevIncome = queries.byCategory(userId, c, w.prevFrom(), w.prevTo(), "INCOME", filter);
        List<ReportQueries.Expense> top = queries.topExpenses(userId, c, w.from(), w.end(), 5, filter);

        Set<UUID> categoryIds = new HashSet<>();
        Stream.of(curExpense, prevExpense, curIncome, prevIncome).flatMap(List::stream).forEach(x -> categoryIds.add(x.categoryId()));
        top.forEach(x -> categoryIds.add(x.categoryId()));
        Map<UUID, CategoryRef> refs = categories.refs(userId, categoryIds);
        List<ReportResponse.CategoryLine> expenseLines = categoryLines(curExpense, prevExpense, refs, c);
        List<ReportResponse.CategoryLine> incomeLines = categoryLines(curIncome, prevIncome, refs, c);

        boolean monthly = ChronoUnit.DAYS.between(from, to) + 1 > DAILY_SERIES_MAX_DAYS;
        List<ReportResponse.SeriesPoint> series = series(queries.series(userId, c, w.from(), w.end(), monthly, filter), w, monthly, c);
        ReportResponse.Peak high = null;
        ReportResponse.Peak low = null;
        if (kind != Kind.DAILY && !series.isEmpty()) {
            high = series.stream().max(Comparator.comparing(ReportResponse.SeriesPoint::expense))
                    .map(p -> new ReportResponse.Peak(p.date(), p.expense())).orElse(null);
            low = series.stream().min(Comparator.comparing(ReportResponse.SeriesPoint::expense))
                    .map(p -> new ReportResponse.Peak(p.date(), p.expense())).orElse(null);
        }

        List<ReportResponse.TopExpense> topExpenses = top.stream().map(e -> new ReportResponse.TopExpense(e.id(),
                MoneyRules.scaled(e.amount(), c), e.date(), e.description(), e.counterparty(), name(refs.get(e.categoryId())))).toList();
        List<ReportResponse.AccountLine> accountLines = accountLines(userId, c, w, filter);

        List<ReportResponse.BudgetLine> budgetLines = null;
        ReportResponse.Salary salary = null;
        ReportResponse.Lending lending = null;
        ReportResponse.Loans loanSection = null;
        if (filter.isEmpty()) {
            budgetLines = budgetLines(userId, c, w.end());
            salary = new ReportResponse.Salary(MoneyRules.scaled(cur.salary(), c), summary.expense(), percent(cur.expense(), cur.salary()));
            lending = lending(userId, c, w);
            loanSection = loans(userId, c, cur);
        }
        List<Insight> insights = Insights.of(kind, w, summary, comparison, expenseLines, budgetLines, loanSection, c);
        return new ReportResponse(kind, from, to, c, today, w.complete(), BASIS, filters, summary, comparison, expenseLines,
                incomeLines, monthly ? ReportResponse.Granularity.MONTH : ReportResponse.Granularity.DAY, series, high, low,
                topExpenses, accountLines, budgetLines, salary, lending, loanSection, insights,
                queries.otherCurrencyCount(userId, c, w.from(), w.end()));
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
                change(cur.income(), prev.income()), change(cur.expense(), prev.expense()));
    }

    private static List<ReportResponse.CategoryLine> categoryLines(List<ReportQueries.CategoryTotal> cur, List<ReportQueries.CategoryTotal> prev,
                                                                   Map<UUID, CategoryRef> refs, String c) {
        BigDecimal total = cur.stream().map(ReportQueries.CategoryTotal::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<UUID, BigDecimal> before = prev.stream().collect(Collectors.toMap(ReportQueries.CategoryTotal::categoryId,
                ReportQueries.CategoryTotal::total));
        return cur.stream().map(x -> {
            BigDecimal previous = before.getOrDefault(x.categoryId(), BigDecimal.ZERO);
            return new ReportResponse.CategoryLine(x.categoryId(), name(refs.get(x.categoryId())), MoneyRules.scaled(x.total(), c),
                    x.count(), percent(x.total(), total), MoneyRules.scaled(previous, c), change(x.total(), previous));
        }).toList();
    }

    /** Zero-filled from the start of the report to how far it has run. */
    private static List<ReportResponse.SeriesPoint> series(List<ReportQueries.Point> points, Window w, boolean monthly, String c) {
        Map<LocalDate, ReportQueries.Point> byDate = points.stream().collect(Collectors.toMap(ReportQueries.Point::date, Function.identity()));
        List<ReportResponse.SeriesPoint> out = new ArrayList<>();
        LocalDate d = monthly ? w.from().withDayOfMonth(1) : w.from();
        while (!d.isAfter(w.end())) {
            ReportQueries.Point p = byDate.get(d);
            BigDecimal income = p == null ? BigDecimal.ZERO : p.income();
            BigDecimal expense = p == null ? BigDecimal.ZERO : p.expense();
            out.add(new ReportResponse.SeriesPoint(d, MoneyRules.scaled(income, c), MoneyRules.scaled(expense, c),
                    MoneyRules.scaled(income.subtract(expense), c)));
            d = monthly ? d.plusMonths(1) : d.plusDays(1);
        }
        return out;
    }

    private List<ReportResponse.AccountLine> accountLines(UUID userId, String c, Window w, Filter filter) {
        Map<UUID, ReportQueries.AccountFlow> flows = queries.accountFlows(userId, c, w.from(), w.end());
        return accounts.list(userId, true).stream()
                .filter(a -> a.currency().equals(c))
                .filter(a -> filter.accountId() == null ? a.active() || flows.containsKey(a.id()) : a.id().equals(filter.accountId()))
                .map(a -> {
                    ReportQueries.AccountFlow f = flows.getOrDefault(a.id(), new ReportQueries.AccountFlow(BigDecimal.ZERO, BigDecimal.ZERO));
                    return new ReportResponse.AccountLine(a.id(), a.name(), a.type(), MoneyRules.scaled(f.inflow(), c),
                            MoneyRules.scaled(f.outflow(), c), MoneyRules.scaled(f.inflow().subtract(f.outflow()), c), a.currentBalance());
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

    private ReportResponse.Lending lending(UUID userId, String c, Window w) {
        ReportQueries.DebtActivity a = queries.debtActivity(userId, c, w.from(), w.end());
        DebtSummaryResponse.CurrencyTotals now = debts.summary(userId).currencies().stream()
                .filter(x -> x.currency().equals(c)).findFirst().orElse(null);
        return new ReportResponse.Lending(MoneyRules.scaled(a.lent(), c), MoneyRules.scaled(a.repaidToYou(), c),
                MoneyRules.scaled(now == null ? BigDecimal.ZERO : now.owedToYou(), c),
                MoneyRules.scaled(a.borrowed(), c), MoneyRules.scaled(a.youRepaid(), c),
                MoneyRules.scaled(now == null ? BigDecimal.ZERO : now.youOwe(), c));
    }

    private ReportResponse.Loans loans(UUID userId, String c, Totals cur) {
        LoanSummaryResponse s = loans.summary(userId, 30);
        LoanSummaryResponse.CurrencyTotals t = s.currencies().stream().filter(x -> x.currency().equals(c)).findFirst().orElse(null);
        BigDecimal dueSoon = s.upcoming().stream().filter(e -> e.currency().equals(c))
                .map(LoanSummaryResponse.UpcomingEmi::emiAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReportResponse.Loans(MoneyRules.scaled(cur.principalRepaid(), c), MoneyRules.scaled(cur.interest(), c),
                MoneyRules.scaled(t == null ? BigDecimal.ZERO : t.outstandingPrincipal(), c),
                MoneyRules.scaled(t == null ? BigDecimal.ZERO : t.monthlyEmi(), c), MoneyRules.scaled(dueSoon, c));
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
