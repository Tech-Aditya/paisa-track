package com.paisatrack.modules.transaction;

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

import com.paisatrack.modules.category.CategoryRef;
import com.paisatrack.modules.category.CategoryService;

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

    /** Income and expense of one currency on one day, for converting with that day's rate. */
    public record DayTotals(String currency, LocalDate date, BigDecimal income, BigDecimal expense, long incomeCount, long expenseCount) {
    }

    /** Spending of one currency on one day in one top-level category. */
    public record DayCategorySpend(String currency, LocalDate date, UUID categoryId, BigDecimal total, long count) {
    }

    /** Every currency, per day; days without income or spending are absent. */
    @Transactional(readOnly = true)
    public List<DayTotals> dayTotals(UUID userId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select currency, transaction_date,
                  coalesce(sum(amount) filter (where type = 'INCOME'), 0) as income,
                  coalesce(sum(amount) filter (where type = 'EXPENSE'), 0) as expense,
                  count(*) filter (where type = 'INCOME') as income_count,
                  count(*) filter (where type = 'EXPENSE') as expense_count
                from transactions
                where user_id = :userId and status = 'ACTIVE' and type in ('INCOME', 'EXPENSE')
                  and transaction_date between :from and :to
                group by 1, 2""",
                params(userId, null, from, to),
                (rs, row) -> new DayTotals(rs.getString("currency"), rs.getObject("transaction_date", LocalDate.class),
                        rs.getBigDecimal("income"), rs.getBigDecimal("expense"), rs.getLong("income_count"), rs.getLong("expense_count")));
    }

    /** Spending per currency, day and top-level category. */
    @Transactional(readOnly = true)
    public List<DayCategorySpend> dayCategorySpend(UUID userId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select t.currency, t.transaction_date, coalesce(c.parent_id, c.id) as category_id, sum(t.amount) as total, count(*) as cnt
                from transactions t join categories c on c.id = t.category_id
                where t.user_id = :userId and t.status = 'ACTIVE' and t.type = 'EXPENSE'
                  and t.transaction_date between :from and :to
                group by 1, 2, 3""",
                params(userId, null, from, to),
                (rs, row) -> new DayCategorySpend(rs.getString("currency"), rs.getObject("transaction_date", LocalDate.class),
                        rs.getObject("category_id", UUID.class), rs.getBigDecimal("total"), rs.getLong("cnt")));
    }

    /** Names of top-level categories, for labelling converted breakdowns. */
    @Transactional(readOnly = true)
    public Map<UUID, String> categoryNames(UUID userId, Collection<UUID> ids) {
        return categories.refs(userId, ids).values().stream().collect(Collectors.toMap(CategoryRef::id, CategoryRef::name));
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

    private static MapSqlParameterSource params(UUID userId, String currency, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("currency", currency)
                .addValue("from", from)
                .addValue("to", to);
    }
}
