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
 * Read-only aggregates for reports, computed in PostgreSQL over the ledger. Rows are grouped by
 * currency and day so each can be converted with the rate for its own date; only ACTIVE
 * transactions count, the same rules as the transaction list, daily summary and dashboard.
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

        static final Totals ZERO = new Totals(BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Totals of one currency on one day; {@code entries} is how many transactions are behind them. */
    record TotalsRow(String currency, LocalDate date, long entries, Totals totals) {
    }

    record CategoryRow(String currency, LocalDate date, UUID categoryId, BigDecimal total, long count) {
    }

    record DayRow(String currency, LocalDate date, BigDecimal income, BigDecimal expense) {
    }

    record Expense(UUID id, String currency, BigDecimal amount, LocalDate date, String description, String counterparty, UUID categoryId) {
    }

    record AccountFlow(BigDecimal inflow, BigDecimal outflow) {
    }

    record DebtActivity(String currency, BigDecimal lent, BigDecimal borrowed, BigDecimal repaidToYou, BigDecimal youRepaid) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    ReportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every kind of money movement, kept apart: income, expenses (of which loan interest), transfers
     * (of which card bill payments and investments), loan principal and disbursements.
     */
    List<TotalsRow> totals(UUID userId, LocalDate from, LocalDate to, Filter f) {
        MapSqlParameterSource p = params(userId, from, to);
        return jdbc.query("""
                select t.currency, t.transaction_date, count(*) as entries,
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
                where t.user_id = :userId and t.status = 'ACTIVE'
                  and t.transaction_date between :from and :to""" + filter(f, p) + """

                group by t.currency, t.transaction_date""",
                p,
                (rs, row) -> new TotalsRow(rs.getString("currency"), rs.getObject("transaction_date", LocalDate.class), rs.getLong("entries"),
                        new Totals(rs.getBigDecimal("income"), rs.getLong("income_count"), rs.getBigDecimal("expense"),
                                rs.getLong("expense_count"), rs.getBigDecimal("interest"), rs.getBigDecimal("transfers"),
                                rs.getLong("transfer_count"), rs.getBigDecimal("card_payments"), rs.getBigDecimal("invested"),
                                rs.getBigDecimal("withdrawn"), rs.getBigDecimal("principal"), rs.getBigDecimal("disbursed"),
                                rs.getBigDecimal("salary"))));
    }

    /** EXPENSE or INCOME totals rolled up to top-level categories, per currency and day. */
    List<CategoryRow> byCategory(UUID userId, LocalDate from, LocalDate to, String type, Filter f) {
        MapSqlParameterSource p = params(userId, from, to).addValue("type", type);
        return jdbc.query("""
                select t.currency, t.transaction_date, coalesce(c.parent_id, c.id) as category_id, sum(t.amount) as total, count(*) as cnt
                from transactions t join categories c on c.id = t.category_id
                where t.user_id = :userId and t.status = 'ACTIVE' and t.type = :type
                  and t.transaction_date between :from and :to""" + filter(f, p) + """

                group by 1, 2, 3""",
                p,
                (rs, row) -> new CategoryRow(rs.getString("currency"), rs.getObject("transaction_date", LocalDate.class),
                        rs.getObject("category_id", UUID.class), rs.getBigDecimal("total"), rs.getLong("cnt")));
    }

    /** Income and expense per currency and day; days without activity are absent. */
    List<DayRow> daily(UUID userId, LocalDate from, LocalDate to, Filter f) {
        MapSqlParameterSource p = params(userId, from, to);
        return jdbc.query("""
                select t.currency, t.transaction_date,
                  coalesce(sum(t.amount) filter (where t.type = 'INCOME'), 0) as income,
                  coalesce(sum(t.amount) filter (where t.type = 'EXPENSE'), 0) as expense
                from transactions t
                where t.user_id = :userId and t.status = 'ACTIVE'
                  and t.type in ('INCOME', 'EXPENSE') and t.transaction_date between :from and :to""" + filter(f, p) + """

                group by 1, 2""",
                p,
                (rs, row) -> new DayRow(rs.getString("currency"), rs.getObject("transaction_date", LocalDate.class),
                        rs.getBigDecimal("income"), rs.getBigDecimal("expense")));
    }

    /** The largest expenses of each currency, biggest first; ties go to the earliest recorded. */
    List<Expense> topExpenses(UUID userId, LocalDate from, LocalDate to, int limit, Filter f) {
        MapSqlParameterSource p = params(userId, from, to).addValue("limit", limit);
        return jdbc.query("""
                select id, currency, amount, transaction_date, description, counterparty, category_id from (
                  select t.*, row_number() over (partition by t.currency order by t.amount desc, t.created_at) as rn
                  from transactions t
                  where t.user_id = :userId and t.status = 'ACTIVE' and t.type = 'EXPENSE'
                    and t.transaction_date between :from and :to""" + filter(f, p) + """

                ) ranked where rn <= :limit""",
                p,
                (rs, row) -> new Expense(rs.getObject("id", UUID.class), rs.getString("currency"), rs.getBigDecimal("amount"),
                        rs.getObject("transaction_date", LocalDate.class), rs.getString("description"),
                        rs.getString("counterparty"), rs.getObject("category_id", UUID.class)));
    }

    /** Money in and out of each account in the range, in the account's own currency; a transfer counts on both sides. */
    Map<UUID, AccountFlow> accountFlows(UUID userId, LocalDate from, LocalDate to) {
        Map<UUID, AccountFlow> result = new HashMap<>();
        jdbc.query("""
                select account_id, sum(inflow) as inflow, sum(outflow) as outflow from (
                    select account_id,
                      case when type in ('INCOME', 'LOAN_DISBURSEMENT') then amount else 0 end as inflow,
                      case when type in ('INCOME', 'LOAN_DISBURSEMENT') then 0 else amount end as outflow
                    from transactions
                    where user_id = :userId and status = 'ACTIVE' and transaction_date between :from and :to
                    union all
                    select to_account_id, coalesce(to_amount, amount), 0
                    from transactions
                    where user_id = :userId and status = 'ACTIVE' and type = 'TRANSFER'
                      and transaction_date between :from and :to
                ) flows
                group by account_id""",
                params(userId, from, to),
                rs -> {
                    result.put(rs.getObject("account_id", UUID.class), new AccountFlow(rs.getBigDecimal("inflow"), rs.getBigDecimal("outflow")));
                });
        return result;
    }

    /** Lending and borrowing started, and repayments made either way, per currency. Never income or spending. */
    List<DebtActivity> debtActivity(UUID userId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select currency, sum(lent) as lent, sum(borrowed) as borrowed, sum(repaid_to_you) as repaid_to_you, sum(you_repaid) as you_repaid
                from (
                  select currency,
                    case when direction = 'LENT' then principal else 0 end as lent,
                    case when direction = 'BORROWED' then principal else 0 end as borrowed,
                    0 as repaid_to_you, 0 as you_repaid
                  from debts where user_id = :userId and deleted_at is null and start_date between :from and :to
                  union all
                  select d.currency, 0, 0,
                    case when d.direction = 'LENT' then r.amount else 0 end,
                    case when d.direction = 'BORROWED' then r.amount else 0 end
                  from debt_repayments r join debts d on d.id = r.debt_id
                  where r.user_id = :userId and d.deleted_at is null and r.voided_at is null
                    and r.repayment_date between :from and :to
                ) activity
                group by currency""",
                params(userId, from, to),
                (rs, row) -> new DebtActivity(rs.getString("currency"), rs.getBigDecimal("lent"), rs.getBigDecimal("borrowed"),
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

    private static MapSqlParameterSource params(UUID userId, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("from", from)
                .addValue("to", to);
    }
}
