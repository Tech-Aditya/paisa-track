package com.paisatrack.modules.loan;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.paisatrack.modules.account.LiabilityProvider;
import com.paisatrack.modules.category.CategoryUsageProvider;

/**
 * Loan facts other modules need: outstanding principal for net worth, and whether a category is a
 * loan's interest category. A standalone bean so accounts and categories don't depend on LoanService.
 */
@Component
class LoanLedgerQueries implements LiabilityProvider, CategoryUsageProvider {

    private final NamedParameterJdbcTemplate jdbc;

    LoanLedgerQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Principal minus active principal payments (EMI principal and prepayments), per currency. */
    @Override
    public Map<String, BigDecimal> outstandingByCurrency(UUID userId) {
        Map<String, BigDecimal> result = new HashMap<>();
        jdbc.query("""
                select l.currency, sum(l.principal - coalesce(p.paid, 0)) as outstanding
                from loans l
                left join (select loan_id, sum(principal_amount) as paid from loan_payments
                           where voided_at is null group by loan_id) p on p.loan_id = l.id
                where l.user_id = :userId and l.deleted_at is null
                group by l.currency""",
                new MapSqlParameterSource("userId", userId),
                rs -> {
                    BigDecimal outstanding = rs.getBigDecimal("outstanding");
                    if (outstanding.signum() > 0) {
                        result.put(rs.getString("currency"), outstanding);
                    }
                });
        return result;
    }

    @Override
    public boolean isUsed(UUID userId, UUID categoryId) {
        Boolean used = jdbc.queryForObject(
                "select exists(select 1 from loans where user_id = :userId and interest_category_id = :categoryId)",
                new MapSqlParameterSource().addValue("userId", userId).addValue("categoryId", categoryId), Boolean.class);
        return Boolean.TRUE.equals(used);
    }
}
