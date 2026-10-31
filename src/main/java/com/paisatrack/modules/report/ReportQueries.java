package com.paisatrack.modules.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only aggregates for reports, computed in PostgreSQL over the ledger (one pass per section).
 * Only ACTIVE transactions in one currency count, the same rules as the transaction list, daily
 * summary and dashboard, so every figure reconciles with the entries behind it.
 */
@Component
class ReportQueries {

    /** Optional narrowing for custom reports. Null fields mean "all". */
    record Filter(UUID accountId, Collection<UUID> categoryIds, String type) {
        static final Filter NONE = new Filter(null, null, null);

        boolean isEmpty() {
            return accountId == null && categoryIds == null && type == null;
        }
    }

    record Totals(BigDecimal income, long incomeCount, BigDecimal expense, long expenseCount, BigDecimal interest,
                  BigDecimal transfers, long transferCount, BigDecimal cardPayments, BigDecimal invested, BigDecimal withdrawn,
                  BigDecimal principalRepaid, BigDecimal disbursed, BigDecimal salary) {
    }

    record CategoryTotal(UUID categoryId, BigDecimal total, long count) {
    }

    record Point(LocalDate date, BigDecimal income, BigDecimal expense) {
    }

    record Expense(UUID id, BigDecimal amount, LocalDate date, String description, String counterparty, UUID categoryId) {
    }

    record AccountFlow(BigDecimal inflow, BigDecimal outflow) {
    }

    record DebtActivity(BigDecimal lent, BigDecimal borrowed, BigDecimal repaidToYou, BigDecimal youRepaid) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    ReportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every kind of money movement, kept apart: income, expenses (of which loan interest), transfers
     * (of which card bill payments and investments), loan principal and disbursements.
     */
    Totals totals(UUID userId, String currency, LocalDate from, LocalDate to, Filter f) {
        MapSqlParameterSource p = params(userId, currency, from, to);
        return jdbc.queryForObject("""
                select
                  coalesce(sum(t.amount) filter (where t.type = 'INCOME'), 0) as income,
                  count(*) filter (where t.type = 'INCOME') as income_count,
                  coalesce(sum(t.amount) filter (where t.type = 'EXPENSE'), 0) as expense,
                  count(*) filter (where t.type = 'EXPENSE') as expense_count,
                  coalesce(sum(t.amount) filter (where t.type = 'EXPENSE' and t.loan_id is not null), 0) as interest,
                  coalesce(sum(t.amount) filter (where t.type = 'TRANSFER'), 0) as transfers,
                  count(*) filter (where t.type = 'TRANSFER') as transfer_count,
                  coalesce(sum(t.amount) filter (where t.type = 'TRANSFER' and ta.type = 'CREDIT_CARD'), 0) as card_payments,
                  coalesce(sum(t.amount) filter (where t.type = 'TRANSFER' and ta.type = 'INVESTMENT' and fa.type <> 'INVESTMENT'), 0) as invested,
                  coalesce(sum(t.amount) filter (where t.type = 'TRANSFER' and fa.type = 'INVESTMENT' and ta.type <> 'INVESTMENT'), 0) as withdrawn,
                  coalesce(sum(t.amount) filter (where t.type = 'LOAN_PRINCIPAL'), 0) as principal,
                  coalesce(sum(t.amount) filter (where t.type = 'LOAN_DISBURSEMENT'), 0) as disbursed,
                  coalesce(sum(t.amount) filter (where t.type = 'INCOME' and s.type = 'SALARY'), 0) as salary
                from transactions t
                join accounts fa on fa.id = t.account_id
                left join accounts ta on ta.id = t.to_account_id
                left join income_sources s on s.id = t.income_source_id
                where t.user_id = :userId and t.status = 'ACTIVE' and t.currency = :currency
                  and t.transaction_date between :from and :to""" + filter(f, p),
                p,
                (rs, row) -> new Totals(rs.getBigDecimal("income"), rs.getLong("income_count"), rs.getBigDecimal("expense"),
                        rs.getLong("expense_count"), rs.getBigDecimal("interest"), rs.getBigDecimal("transfers"),
                        rs.getLong("transfer_count"), rs.getBigDecimal("card_payments"), rs.getBigDecimal("invested"),
                        rs.getBigDecimal("withdrawn"), rs.getBigDecimal("principal"), rs.getBigDecimal("disbursed"),
                        rs.getBigDecimal("salary")));
    }

    /** Entries in other currencies in the range: counted, never summed. */
    long otherCurrencyCount(UUID userId, String currency, LocalDate from, LocalDate to) {
        Long n = jdbc.queryForObject("""
                select count(*) from transactions
                where user_id = :userId and status = 'ACTIVE' and currency <> :currency
                  and transaction_date between :from and :to""", params(userId, currency, from, to), Long.class);
        return n == null ? 0 : n;
    }

    /** EXPENSE or INCOME totals rolled up to top-level categories, largest first. */
    List<CategoryTotal> byCategory(UUID userId, String currency, LocalDate from, LocalDate to, String type, Filter f) {
        MapSqlParameterSource p = params(userId, currency, from, to).addValue("type", type);
        return jdbc.query("""
                select coalesce(c.parent_id, c.id) as category_id, sum(t.amount) as total, count(*) as cnt
                from transactions t join categories c on c.id = t.category_id
                where t.user_id = :userId and t.status = 'ACTIVE' and t.currency = :currency and t.type = :type
                  and t.transaction_date between :from and :to""" + filter(f, p) + """

                group by 1 order by total desc""",
                p,
                (rs, row) -> new CategoryTotal(rs.getObject("category_id", UUID.class), rs.getBigDecimal("total"), rs.getLong("cnt")));
    }

    /** Income and expense per day or per month; buckets without activity are absent. */
    List<Point> series(UUID userId, String currency, LocalDate from, LocalDate to, boolean monthly, Filter f) {
        MapSqlParameterSource p = params(userId, currency, from, to);
        String bucket = monthly ? "date_trunc('month', t.transaction_date)::date" : "t.transaction_date";
        return jdbc.query("select " + bucket + " as bucket," + """

                  coalesce(sum(t.amount) filter (where t.type = 'INCOME'), 0) as income,
                  coalesce(sum(t.amount) filter (where t.type = 'EXPENSE'), 0) as expense
                from transactions t
                where t.user_id = :userId and t.status = 'ACTIVE' and t.currency = :currency
                  and t.type in ('INCOME', 'EXPENSE') and t.transaction_date between :from and :to""" + filter(f, p) + """

                group by 1 order by 1""",
                p,
                (rs, row) -> new Point(rs.getObject("bucket", LocalDate.class), rs.getBigDecimal("income"), rs.getBigDecimal("expense")));
    }

    /** The largest expenses, biggest first; ties go to the earliest recorded. */
    List<Expense> topExpenses(UUID userId, String currency, LocalDate from, LocalDate to, int limit, Filter f) {
        MapSqlParameterSource p = params(userId, currency, from, to).addValue("limit", limit);
        return jdbc.query("""
                select t.id, t.amount, t.transaction_date, t.description, t.counterparty, t.category_id
                from transactions t
                where t.user_id = :userId and t.status = 'ACTIVE' and t.currency = :currency and t.type = 'EXPENSE'
                  and t.transaction_date between :from and :to""" + filter(f, p) + """

                order by t.amount desc, t.created_at limit :limit""",
                p,
                (rs, row) -> new Expense(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"),
                        rs.getObject("transaction_date", LocalDate.class), rs.getString("description"),
                        rs.getString("counterparty"), rs.getObject("category_id", UUID.class)));
    }

    /** Money in and out of each account in the range; a transfer counts on both sides. */
    Map<UUID, AccountFlow> accountFlows(UUID userId, String currency, LocalDate from, LocalDate to) {
        Map<UUID, AccountFlow> result = new HashMap<>();
        jdbc.query("""
                select account_id, sum(inflow) as inflow, sum(outflow) as outflow from (
                    select account_id,
                      case when type in ('INCOME', 'LOAN_DISBURSEMENT') then amount else 0 end as inflow,
                      case when type in ('INCOME', 'LOAN_DISBURSEMENT') then 0 else amount end as outflow
                    from transactions
                    where user_id = :userId and status = 'ACTIVE' and currency = :currency and transaction_date between :from and :to
                    union all
                    select to_account_id, amount, 0
                    from transactions
                    where user_id = :userId and status = 'ACTIVE' and currency = :currency and type = 'TRANSFER'
                      and transaction_date between :from and :to
                ) flows
                group by account_id""",
                params(userId, currency, from, to),
                rs -> {
                    result.put(rs.getObject("account_id", UUID.class), new AccountFlow(rs.getBigDecimal("inflow"), rs.getBigDecimal("outflow")));
                });
        return result;
    }

    /** Lending and borrowing started, and repayments made either way, in the range. Never income or spending. */
    DebtActivity debtActivity(UUID userId, String currency, LocalDate from, LocalDate to) {
        MapSqlParameterSource p = params(userId, currency, from, to);
        return jdbc.queryForObject("""
                select
                  (select coalesce(sum(principal), 0) from debts
                   where user_id = :userId and currency = :currency and deleted_at is null and direction = 'LENT'
                     and start_date between :from and :to) as lent,
                  (select coalesce(sum(principal), 0) from debts
                   where user_id = :userId and currency = :currency and deleted_at is null and direction = 'BORROWED'
                     and start_date between :from and :to) as borrowed,
                  coalesce(sum(r.amount) filter (where d.direction = 'LENT'), 0) as repaid_to_you,
                  coalesce(sum(r.amount) filter (where d.direction = 'BORROWED'), 0) as you_repaid
                from debt_repayments r join debts d on d.id = r.debt_id
                where r.user_id = :userId and d.currency = :currency and d.deleted_at is null and r.voided_at is null
                  and r.repayment_date between :from and :to""",
                p,
                (rs, row) -> new DebtActivity(rs.getBigDecimal("lent"), rs.getBigDecimal("borrowed"),
                        rs.getBigDecimal("repaid_to_you"), rs.getBigDecimal("you_repaid")));
    }

    private static String filter(Filter f, MapSqlParameterSource p) {
        if (f == null || f.isEmpty()) {
            return "";
        }
        StringBuilder sql = new StringBuilder();
        if (f.accountId() != null) {
            sql.append(" and (t.account_id = :accountId or t.to_account_id = :accountId)");
            p.addValue("accountId", f.accountId());
        }
        if (f.categoryIds() != null) {
            sql.append(" and t.category_id in (:categoryIds)");
            p.addValue("categoryIds", f.categoryIds());
        }
        if (f.type() != null) {
            sql.append(" and t.type = :filterType");
            p.addValue("filterType", f.type());
        }
        return sql.toString();
    }

    private static MapSqlParameterSource params(UUID userId, String currency, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("currency", currency)
                .addValue("from", from)
                .addValue("to", to);
    }
}
