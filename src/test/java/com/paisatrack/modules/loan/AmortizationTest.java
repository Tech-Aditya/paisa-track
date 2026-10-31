package com.paisatrack.modules.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.paisatrack.platform.exception.FieldValidationException;

class AmortizationTest {

    private static final LocalDate FIRST = LocalDate.parse("2026-01-05");

    @Test
    void emiMatchesTheStandardFormula() {
        // ₹10,00,000 at 9% for 20 years: the well-known ₹8,997.26 EMI.
        assertThat(Amortization.emi(new BigDecimal("1000000"), new BigDecimal("9"), 240, 2)).isEqualByComparingTo("8997.26");
        // ₹5,00,000 at 10.5% for 5 years.
        assertThat(Amortization.emi(new BigDecimal("500000"), new BigDecimal("10.5"), 60, 2)).isEqualByComparingTo("10746.95");
        assertThat(Amortization.emi(new BigDecimal("12000"), BigDecimal.ZERO, 12, 2)).isEqualByComparingTo("1000.00");
    }

    @Test
    void scheduleRepaysExactlyThePrincipalAndSplitsEachEmi() {
        BigDecimal principal = new BigDecimal("500000");
        BigDecimal emi = Amortization.emi(principal, new BigDecimal("10.5"), 60, 2);
        List<Amortization.Row> rows = Amortization.schedule(principal, new BigDecimal("10.5"), emi, FIRST, 1, 60, 2);

        assertThat(rows).hasSize(60);
        assertThat(rows.stream().map(Amortization.Row::principal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(principal);
        assertThat(rows.getLast().closing()).isEqualByComparingTo("0");
        Amortization.Row first = rows.getFirst();
        assertThat(first.interest()).isEqualByComparingTo("4375.00"); // 5,00,000 × 10.5% / 12
        assertThat(first.principal().add(first.interest())).isEqualByComparingTo(emi);
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).opening()).isEqualByComparingTo(rows.get(i - 1).closing());
            assertThat(rows.get(i).interest()).isLessThan(rows.get(i - 1).interest());
        }
        // The last EMI only differs from the others by rounding.
        assertThat(rows.getLast().emi().subtract(emi).abs()).isLessThan(new BigDecimal("1"));
    }

    @Test
    void dueDatesFollowTheFirstEmiDateWithoutDrifting() {
        List<Amortization.Row> rows = Amortization.schedule(new BigDecimal("3000"), BigDecimal.ZERO, new BigDecimal("1000"),
                LocalDate.parse("2026-01-31"), 1, 3, 2);
        assertThat(rows).extracting(Amortization.Row::dueDate)
                .containsExactly(LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-28"), LocalDate.parse("2026-03-31"));
    }

    @Test
    void sameEmiOnALowerBalanceEndsSooner() {
        BigDecimal emi = Amortization.emi(new BigDecimal("500000"), new BigDecimal("10.5"), 60, 2);
        List<Amortization.Row> after = Amortization.schedule(new BigDecimal("250000"), new BigDecimal("10.5"), emi, FIRST, 13, null, 2);
        assertThat(after.getFirst().number()).isEqualTo(13);
        assertThat(after.size()).isLessThan(48);
        assertThat(after.getLast().closing()).isEqualByComparingTo("0");
    }

    @Test
    void emiThatCannotCoverInterestIsRejected() {
        assertThatThrownBy(() -> Amortization.schedule(new BigDecimal("1000000"), new BigDecimal("12"), new BigDecimal("9000"),
                FIRST, 1, 240, 2))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("too small");
    }
}
