package com.paisatrack.modules.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.paisatrack.modules.account.AccountFlowProvider;
import com.paisatrack.modules.category.CategoryUsageProvider;
import com.paisatrack.modules.income.IncomeReceiptProvider;

/**
 * Aggregations done in SQL so balances and summaries never load whole histories into memory.
 * Every query is scoped by user_id and counts ACTIVE transactions only (except usage checks).
 */
@Repository
class TransactionSqlQueries implements AccountFlowProvider, CategoryUsageProvider, IncomeReceiptProvider {

    private final NamedParameterJdbcTemplate jdbc;

    TransactionSqlQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Income and incoming transfers add; expenses and outgoing transfers subtract. */
    @Override
    public Map<UUID, BigDecimal> netFlows(UUID userId) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        jdbc.query("""
                select account_id, sum(delta) as net from (
                    select account_id, case when type in ('INCOME', 'LOAN_DISBURSEMENT') then amount else -amount end as delta
                    from transactions where user_id = :userId and status = 'ACTIVE'
                    union all
                    select to_account_id, amount
                    from transactions where user_id = :userId and status = 'ACTIVE' and type = 'TRANSFER'
                ) flows
                group by account_id""",
                new MapSqlParameterSource("userId", userId),
                rs -> {
                    result.put(rs.getObject("account_id", UUID.class), rs.getBigDecimal("net"));
                });
        return result;
    }

    @Override
    public boolean isUsed(UUID userId, UUID categoryId) {
        Boolean used = jdbc.queryForObject(
                "select exists(select 1 from transactions where user_id = :userId and category_id = :categoryId)",
                new MapSqlParameterSource().addValue("userId", userId).addValue("categoryId", categoryId),
                Boolean.class);
        return Boolean.TRUE.equals(used);
    }

    @Override
    public Map<UUID, Map<LocalDate, BigDecimal>> receivedByPeriod(UUID userId, LocalDate fromMonth, LocalDate toMonth) {
        Map<UUID, Map<LocalDate, BigDecimal>> result = new HashMap<>();
        jdbc.query("""
                select income_source_id, income_period, sum(amount) as total
                from transactions
                where user_id = :userId and status = 'ACTIVE' and type = 'INCOME'
                  and income_source_id is not null and income_period between :from and :to
                group by income_source_id, income_period""",
                range(userId, fromMonth, toMonth),
                rs -> {
                    result.computeIfAbsent(rs.getObject("income_source_id", UUID.class), k -> new HashMap<>())
                            .put(rs.getObject("income_period", LocalDate.class), rs.getBigDecimal("total"));
                });
        return result;
    }

    record TypeTotal(String currency, TransactionType type, long count, BigDecimal total) {
    }

    record TopExpense(String currency, UUID id, BigDecimal amount, String description, UUID categoryId) {
    }

    record CategoryAmount(String currency, UUID categoryId, BigDecimal total, long count) {
    }

    /** Income and expense totals per currency and type. Transfers are excluded by definition. */
    List<TypeTotal> totals(UUID userId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select currency, type, count(*) as cnt, sum(amount) as total
                from transactions
                where user_id = :userId and status = 'ACTIVE' and type in ('INCOME', 'EXPENSE')
                  and transaction_date between :from and :to
                group by currency, type""",
                range(userId, from, to),
                (rs, row) -> new TypeTotal(rs.getString("currency"), TransactionType.valueOf(rs.getString("type")),
                        rs.getLong("cnt"), rs.getBigDecimal("total")));
    }

    /** The largest expense per currency; ties go to the earliest recorded. */
    List<TopExpense> highestExpenses(UUID userId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select distinct on (currency) currency, id, amount, description, category_id
                from transactions
                where user_id = :userId and status = 'ACTIVE' and type = 'EXPENSE'
                  and transaction_date between :from and :to
                order by currency, amount desc, created_at""",
                range(userId, from, to),
                (rs, row) -> new TopExpense(rs.getString("currency"), rs.getObject("id", UUID.class),
                        rs.getBigDecimal("amount"), rs.getString("description"), rs.getObject("category_id", UUID.class)));
    }

    /** Expense totals rolled up to top-level categories, largest first. */
    List<CategoryAmount> expenseByCategory(UUID userId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select t.currency, coalesce(c.parent_id, c.id) as category_id, sum(t.amount) as total, count(*) as cnt
                from transactions t
                join categories c on c.id = t.category_id
                where t.user_id = :userId and t.status = 'ACTIVE' and t.type = 'EXPENSE'
                  and t.transaction_date between :from and :to
                group by t.currency, coalesce(c.parent_id, c.id)
                order by total desc""",
                range(userId, from, to),
                (rs, row) -> new CategoryAmount(rs.getString("currency"), rs.getObject("category_id", UUID.class),
                        rs.getBigDecimal("total"), rs.getLong("cnt")));
    }

    private static MapSqlParameterSource range(UUID userId, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("from", from)
                .addValue("to", to);
    }
}
