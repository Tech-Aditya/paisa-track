package com.paisatrack.modules.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.currency.CurrencyService;
import com.paisatrack.modules.currency.RateBook;
import com.paisatrack.modules.recurring.RecurringService;
import com.paisatrack.modules.recurring.RecurringType;
import com.paisatrack.modules.recurring.dto.OccurrenceResponse;
import com.paisatrack.modules.report.ReportQueries.DayRow;
import com.paisatrack.modules.report.dto.CalendarResponse;
import com.paisatrack.modules.user.UserPreferencesService;
import com.paisatrack.platform.money.MoneyRules;

/**
 * The financial calendar: recorded income and spending per day (converted like the reports),
 * plus everything with a date on it: recurring bills, EMIs, debt due dates and expected income.
 */
@Service
public class CalendarService {

    private final ReportQueries queries;
    private final RecurringService recurring;
    private final CurrencyService currencies;
    private final UserPreferencesService preferences;
    private final NamedParameterJdbcTemplate jdbc;

    CalendarService(ReportQueries queries, RecurringService recurring, CurrencyService currencies,
                    UserPreferencesService preferences, NamedParameterJdbcTemplate jdbc) {
        this.queries = queries;
        this.recurring = recurring;
        this.currencies = currencies;
        this.preferences = preferences;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public CalendarResponse month(UUID userId, YearMonth requested, String requestedCurrency) {
        LocalDate today = preferences.today(userId);
        YearMonth month = requested != null ? requested : YearMonth.from(today);
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        String target = requestedCurrency != null ? requestedCurrency : preferences.defaultCurrency(userId);

        List<DayRow> rows = queries.daily(userId, from, to, ReportQueries.Filter.NONE);
        Set<String> involved = new HashSet<>();
        rows.forEach(r -> involved.add(r.currency()));
        RateBook book = currencies.book(userId, target, involved, today.isBefore(to) ? today : to);
        Map<LocalDate, BigDecimal[]> byDay = new TreeMap<>();
        Map<LocalDate, Long> counts = new TreeMap<>();
        Set<String> unconverted = new TreeSet<>();
        for (DayRow r : rows) {
            var income = book.convert(r.income(), r.currency(), r.date());
            var expense = book.convert(r.expense(), r.currency(), r.date());
            if (income.isEmpty() || expense.isEmpty()) {
                unconverted.add(r.currency());
                continue;
            }
            BigDecimal[] t = byDay.computeIfAbsent(r.date(), d -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            t[0] = t[0].add(income.get());
            t[1] = t[1].add(expense.get());
        }
        jdbc.query("""
                select transaction_date, count(*) from transactions
                where user_id = :userId and status = 'ACTIVE' and type in ('INCOME', 'EXPENSE')
                  and transaction_date between :from and :to group by 1""",
                params(userId, from, to), rs -> {
                    counts.put(rs.getObject(1, LocalDate.class), rs.getLong(2));
                });
        List<CalendarResponse.Day> days = new ArrayList<>();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (var e : byDay.entrySet()) {
            BigDecimal in = MoneyRules.scaled(e.getValue()[0], target);
            BigDecimal out = MoneyRules.scaled(e.getValue()[1], target);
            income = income.add(in);
            expense = expense.add(out);
            days.add(new CalendarResponse.Day(e.getKey(), in, out, counts.getOrDefault(e.getKey(), 0L)));
        }

        List<CalendarResponse.Event> events = new ArrayList<>();
        for (OccurrenceResponse o : recurring.occurrencesBetween(userId, from, to, null)) {
            events.add(new CalendarResponse.Event(o.dueDate(), "RECURRING", o.ruleName(),
                    o.actualAmount() != null ? o.actualAmount() : o.expectedAmount(), o.currency(),
                    o.type() == RecurringType.INCOME ? "IN" : o.type() == RecurringType.TRANSFER ? "MOVE" : "OUT",
                    switch (o.status()) {
                        case CONFIRMED -> "DONE";
                        default -> o.status().name();
                    }, o.ruleId()));
        }
        events.addAll(emis(userId, from, to, today));
        events.addAll(debtsDue(userId, from, to, today));
        events.addAll(expectedIncome(userId, month, today));
        events.sort(Comparator.comparing(CalendarResponse.Event::date).thenComparing(CalendarResponse.Event::kind)
                .thenComparing(CalendarResponse.Event::title, Comparator.nullsLast(Comparator.naturalOrder())));

        return new CalendarResponse(month.toString(), target, today, MoneyRules.scaled(income, target), MoneyRules.scaled(expense, target),
                days, events, List.copyOf(unconverted));
    }

    /** Scheduled EMIs of active loans; paid ones are DONE. */
    private List<CalendarResponse.Event> emis(UUID userId, LocalDate from, LocalDate to, LocalDate today) {
        return jdbc.query("""
                select i.due_date, l.name, i.emi_amount, l.currency, l.id,
                       exists (select 1 from loan_payments p where p.loan_id = l.id and p.kind = 'EMI'
                               and p.installment_number = i.installment_number and p.voided_at is null) as paid
                from loan_installments i join loans l on l.id = i.loan_id
                where l.user_id = :userId and l.deleted_at is null and i.due_date between :from and :to""",
                params(userId, from, to),
                (rs, row) -> {
                    LocalDate due = rs.getObject(1, LocalDate.class);
                    return new CalendarResponse.Event(due, "EMI", "EMI: " + rs.getString(2),
                            MoneyRules.scaled(rs.getBigDecimal(3), rs.getString(4)), rs.getString(4), "OUT",
                            rs.getBoolean(6) ? "DONE" : status(due, today), rs.getObject(5, UUID.class));
                });
    }

    /** Money lent or borrowed that falls due this month, with what is still unpaid. */
    private List<CalendarResponse.Event> debtsDue(UUID userId, LocalDate from, LocalDate to, LocalDate today) {
        return jdbc.query("""
                select d.due_date, d.direction, p.name, d.currency, d.id,
                       d.principal - coalesce((select sum(r.amount) from debt_repayments r
                                               where r.debt_id = d.id and r.voided_at is null), 0) as outstanding,
                       d.written_off_at is not null as written_off, d.principal
                from debts d join people p on p.id = d.person_id
                where d.user_id = :userId and d.deleted_at is null and d.due_date between :from and :to""",
                params(userId, from, to),
                (rs, row) -> {
                    LocalDate due = rs.getObject(1, LocalDate.class);
                    boolean lent = "LENT".equals(rs.getString(2));
                    BigDecimal outstanding = rs.getBigDecimal(6);
                    boolean settled = outstanding.signum() <= 0 || rs.getBoolean(7);
                    return new CalendarResponse.Event(due, "DEBT_DUE",
                            (lent ? rs.getString(3) + " repays you" : "Repay " + rs.getString(3)),
                            MoneyRules.scaled(settled ? rs.getBigDecimal(8) : outstanding, rs.getString(4)), rs.getString(4),
                            lent ? "IN" : "OUT", settled ? "DONE" : status(due, today), rs.getObject(5, UUID.class));
                });
    }

    /** Each active income source's expected pay day this month; DONE once a receipt settles the month. */
    private List<CalendarResponse.Event> expectedIncome(UUID userId, YearMonth month, LocalDate today) {
        LocalDate first = month.atDay(1);
        return jdbc.query("""
                select s.id, s.name, s.currency, e.amount, e.expected_day,
                       exists (select 1 from transactions t where t.income_source_id = s.id and t.income_period = :month
                               and t.status = 'ACTIVE') as received
                from income_sources s
                join lateral (select x.amount, x.expected_day from income_expectations x
                              where x.income_source_id = s.id and x.effective_month <= :month
                              order by x.effective_month desc limit 1) e on true
                where s.user_id = :userId and s.active and s.start_month <= :month and e.amount is not null""",
                new MapSqlParameterSource().addValue("userId", userId).addValue("month", first),
                (rs, row) -> {
                    int day = Math.min(rs.getInt(5), month.lengthOfMonth());
                    LocalDate due = month.atDay(day);
                    return new CalendarResponse.Event(due, "INCOME_EXPECTED", rs.getString(2),
                            MoneyRules.scaled(rs.getBigDecimal(4), rs.getString(3)), rs.getString(3), "IN",
                            rs.getBoolean(6) ? "DONE" : status(due, today), rs.getObject(1, UUID.class));
                });
    }

    private static String status(LocalDate due, LocalDate today) {
        return due.isBefore(today) ? "OVERDUE" : due.isEqual(today) ? "DUE" : "UPCOMING";
    }

    private static MapSqlParameterSource params(UUID userId, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource().addValue("userId", userId).addValue("from", from).addValue("to", to);
    }
}
