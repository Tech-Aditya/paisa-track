package com.paisatrack.income;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.paisatrack.income.dto.ExpectedIncomeResponse.Status;

class IncomeStatusTest {

    private static final LocalDate DUE = LocalDate.parse("2026-10-01");
    private static final BigDecimal EXPECTED = new BigDecimal("50000");

    @Test
    void statusDependsOnAmountReceivedAndDueDate() {
        assertThat(IncomeSourceService.status(EXPECTED, new BigDecimal("50000"), DUE, DUE.plusDays(9))).isEqualTo(Status.RECEIVED);
        assertThat(IncomeSourceService.status(EXPECTED, new BigDecimal("55000"), DUE, DUE)).isEqualTo(Status.RECEIVED);
        assertThat(IncomeSourceService.status(EXPECTED, new BigDecimal("1"), DUE, DUE.plusDays(9))).isEqualTo(Status.PARTIALLY_RECEIVED);
        assertThat(IncomeSourceService.status(EXPECTED, BigDecimal.ZERO, DUE, DUE)).isEqualTo(Status.PENDING);
        assertThat(IncomeSourceService.status(EXPECTED, BigDecimal.ZERO, DUE, DUE.plusDays(1))).isEqualTo(Status.OVERDUE);
        assertThat(IncomeSourceService.status(null, new BigDecimal("10"), null, DUE)).isEqualTo(Status.NOT_EXPECTED);
    }
}
