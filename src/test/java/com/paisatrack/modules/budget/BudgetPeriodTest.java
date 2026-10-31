package com.paisatrack.modules.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class BudgetPeriodTest {

    @Test
    void monthlyPeriodsCoverTheCalendarMonth() {
        LocalDate start = BudgetService.periodStart(BudgetPeriod.MONTHLY, LocalDate.parse("2028-02-17"), DayOfWeek.MONDAY);
        assertThat(start).isEqualTo("2028-02-01");
        assertThat(BudgetService.periodEnd(BudgetPeriod.MONTHLY, start)).isEqualTo("2028-02-29");
    }

    @Test
    void weeklyPeriodsStartOnThePreferredDay() {
        LocalDate saturday = LocalDate.parse("2026-03-14");
        assertThat(BudgetService.periodStart(BudgetPeriod.WEEKLY, saturday, DayOfWeek.MONDAY)).isEqualTo("2026-03-09");
        assertThat(BudgetService.periodStart(BudgetPeriod.WEEKLY, saturday, DayOfWeek.SUNDAY)).isEqualTo("2026-03-08");
        assertThat(BudgetService.periodStart(BudgetPeriod.WEEKLY, saturday, DayOfWeek.SATURDAY)).isEqualTo(saturday);
        assertThat(BudgetService.periodEnd(BudgetPeriod.WEEKLY, LocalDate.parse("2026-03-09"))).isEqualTo("2026-03-15");
    }
}
