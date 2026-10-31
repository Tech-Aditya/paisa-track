package com.paisatrack.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.category.CategoryRef;
import com.paisatrack.category.CategoryService;

/**
 * Aggregates for budgets and the dashboard, always for one currency. Only ACTIVE INCOME and EXPENSE
 * transactions count: transfers and voided entries never do.
 */
@Service
public class TransactionTotalsService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CategoryService categories;

    TransactionTotalsService(NamedParameterJdbcTemplate jdbc, CategoryService categories) {
        this.jdbc = jdbc;
        this.categories = categories;
    }

    public record PeriodTotals(BigDecimal income, BigDecimal expense, long incomeCount, long expenseCount,
                               long otherCurrencyCount) {
    }

    public record DayAmount(LocalDate date, BigDecimal amount) {
    }

    public record MonthTotals(LocalDate month, BigDecimal income, BigDecimal expense) {
    }

    public record CategorySpend(UUID categoryId, String name, BigDecimal total, long count) {
    }

    /** Spending in a date range, optionally limited to some categories. */
    @Transactional(readOnly = true)
    public BigDecimal expenseTotal(UUID userId, String currency, LocalDate from, LocalDate to, Collection<UUID> categoryIds) {
        MapSqlParameterSource params = params(userId, currency, from, to);
        String categoryFilter = "";
        if (categoryIds != null) {
            if (categoryIds.isEmpty()) {
                return BigDecimal.ZERO;
            }
            categoryFilter = " and category_id in (:categoryIds)";
            params.addValue("categoryIds", categoryIds);
        }
        BigDecimal total = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from transactions
                where user_id = :userId and status = 'ACTIVE' and type = 'EXPENSE' and currency = :currency
                  and transaction_date between :from and :to""" + categoryFilter, params, BigDecimal.class);
        return total == null ? BigDecimal.ZERO : total;
    }

    /** Income and expense totals in one currency, plus how many entries in other currencies were left out. */
    @Transactional(readOnly = true)
    public PeriodTotals totals(UUID userId, String currency, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                select
                  coalesce(sum(amount) filter (where type = 'INCOME' and currency = :currency), 0) as income,
                  coalesce(sum(amount) filter (where type = 'EXPENSE' and currency = :currency), 0) as expense,
                  count(*) filter (where type = 'INCOME' and currency = :currency) as income_count,
                  count(*) filter (where type = 'EXPENSE' and currency = :currency) as expense_count,
                  count(*) filter (where currency <> :currency) as other_currency_count
                from transactions
                where user_id = :userId and status = 'ACTIVE' and type in ('INCOME', 'EXPENSE')
                  and transaction_date between :from and :to""",
                params(userId, currency, from, to),
                (rs, row) -> new PeriodTotals(rs.getBigDecimal("income"), rs.getBigDecimal("expense"),
                        rs.getLong("income_count"), rs.getLong("expense_count"), rs.getLong("other_currency_count")));
    }

    /** Spending per day; days without spending are absent. */
    @Transactional(readOnly = true)
    public List<DayAmount> dailyExpenses(UUID userId, String currency, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select transaction_date, sum(amount) as total from transactions
                where user_id = :userId and status = 'ACTIVE' and type = 'EXPENSE' and currency = :currency
                  and transaction_date between :from and :to
                group by transaction_date order by transaction_date""",
                params(userId, currency, from, to),
                (rs, row) -> new DayAmount(rs.getObject("transaction_date", LocalDate.class), rs.getBigDecimal("total")));
    }

    /** Income and spending per calendar month; months without activity are absent. */
    @Transactional(readOnly = true)
    public List<MonthTotals> monthlyTotals(UUID userId, String currency, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select date_trunc('month', transaction_date)::date as month,
                  coalesce(sum(amount) filter (where type = 'INCOME'), 0) as income,
                  coalesce(sum(amount) filter (where type = 'EXPENSE'), 0) as expense
                from transactions
                where user_id = :userId and status = 'ACTIVE' and type in ('INCOME', 'EXPENSE') and currency = :currency
                  and transaction_date between :from and :to
                group by 1 order by 1""",
                params(userId, currency, from, to),
                (rs, row) -> new MonthTotals(rs.getObject("month", LocalDate.class), rs.getBigDecimal("income"),
                        rs.getBigDecimal("expense")));
    }

    /** Spending rolled up to top-level categories, largest first. */
    @Transactional(readOnly = true)
    public List<CategorySpend> expenseByCategory(UUID userId, String currency, LocalDate from, LocalDate to) {
        record Row(UUID categoryId, BigDecimal total, long count) {
        }
        List<Row> rows = jdbc.query("""
                select coalesce(c.parent_id, c.id) as category_id, sum(t.amount) as total, count(*) as cnt
                from transactions t join categories c on c.id = t.category_id
                where t.user_id = :userId and t.status = 'ACTIVE' and t.type = 'EXPENSE' and t.currency = :currency
                  and t.transaction_date between :from and :to
                group by 1 order by total desc""",
                params(userId, currency, from, to),
                (rs, row) -> new Row(rs.getObject("category_id", UUID.class), rs.getBigDecimal("total"), rs.getLong("cnt")));
        Map<UUID, CategoryRef> refs = categories.refs(userId, rows.stream().map(Row::categoryId).collect(Collectors.toSet()));
        return rows.stream()
                .map(r -> new CategorySpend(r.categoryId(),
                        refs.containsKey(r.categoryId()) ? refs.get(r.categoryId()).name() : null, r.total(), r.count()))
                .toList();
    }

    private static MapSqlParameterSource params(UUID userId, String currency, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("currency", currency)
                .addValue("from", from)
                .addValue("to", to);
    }
}
