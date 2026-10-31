package com.paisatrack.modules.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.paisatrack.modules.report.ReportService.Window;
import com.paisatrack.modules.report.dto.ReportResponse.Kind;

class ReportWindowTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void runningMonthIsComparedWithTheSamePointLastMonth() {
        Window w = ReportService.window(Kind.MONTHLY, d("2026-11-01"), d("2026-11-30"), d("2026-11-15"));
        assertThat(w.end()).isEqualTo(d("2026-11-15"));
        assertThat(w.complete()).isFalse();
        assertThat(w.samePoint()).isTrue();
        assertThat(w.prevFrom()).isEqualTo(d("2026-10-01"));
        assertThat(w.prevTo()).isEqualTo(d("2026-10-15"));
        assertThat(w.days()).isEqualTo(15);
    }

    @Test
    void sameDayClampsToAShorterPreviousMonth() {
        Window w = ReportService.window(Kind.MONTHLY, d("2026-03-01"), d("2026-03-31"), d("2026-03-31"));
        assertThat(w.prevFrom()).isEqualTo(d("2026-02-01"));
        assertThat(w.prevTo()).isEqualTo(d("2026-02-28"));
    }

    @Test
    void finishedPeriodsAreComparedInFull() {
        Window month = ReportService.window(Kind.MONTHLY, d("2026-10-01"), d("2026-10-31"), d("2026-11-15"));
        assertThat(month.complete()).isTrue();
        assertThat(month.samePoint()).isFalse();
        assertThat(month.prevFrom()).isEqualTo(d("2026-09-01"));
        assertThat(month.prevTo()).isEqualTo(d("2026-09-30"));

        Window year = ReportService.window(Kind.YEARLY, d("2025-01-01"), d("2025-12-31"), d("2026-11-15"));
        assertThat(year.prevFrom()).isEqualTo(d("2024-01-01"));
        assertThat(year.prevTo()).isEqualTo(d("2024-12-31"));
    }

    @Test
    void runningWeekAndYearUseTheSamePoint() {
        Window week = ReportService.window(Kind.WEEKLY, d("2026-11-09"), d("2026-11-15"), d("2026-11-11"));
        assertThat(week.prevFrom()).isEqualTo(d("2026-11-02"));
        assertThat(week.prevTo()).isEqualTo(d("2026-11-04"));

        Window year = ReportService.window(Kind.YEARLY, d("2026-01-01"), d("2026-12-31"), d("2026-11-15"));
        assertThat(year.prevFrom()).isEqualTo(d("2025-01-01"));
        assertThat(year.prevTo()).isEqualTo(d("2025-11-15"));
    }

    @Test
    void customAndDailyCompareWithTheRangeJustBefore() {
        Window custom = ReportService.window(Kind.CUSTOM, d("2026-10-10"), d("2026-10-19"), d("2026-11-15"));
        assertThat(custom.prevFrom()).isEqualTo(d("2026-09-30"));
        assertThat(custom.prevTo()).isEqualTo(d("2026-10-09"));

        Window day = ReportService.window(Kind.DAILY, d("2026-11-15"), d("2026-11-15"), d("2026-11-15"));
        assertThat(day.prevFrom()).isEqualTo(d("2026-11-14")).isEqualTo(day.prevTo());
        assertThat(day.samePoint()).isFalse();
    }

    @Test
    void weeksStartOnTheChosenDay() {
        assertThat(ReportService.weekStart(d("2026-11-11"), DayOfWeek.MONDAY)).isEqualTo(d("2026-11-09"));
        assertThat(ReportService.weekStart(d("2026-11-11"), DayOfWeek.SUNDAY)).isEqualTo(d("2026-11-08"));
        assertThat(ReportService.weekStart(d("2026-11-08"), DayOfWeek.SUNDAY)).isEqualTo(d("2026-11-08"));
    }

    @Test
    void percentagesAreNullWithoutABase() {
        assertThat(ReportService.percent(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
        assertThat(ReportService.change(BigDecimal.TEN, BigDecimal.ZERO)).isNull();
        assertThat(ReportService.change(new BigDecimal("150"), new BigDecimal("100"))).isEqualByComparingTo("50.0");
        assertThat(ReportService.percent(new BigDecimal("1"), new BigDecimal("3"))).isEqualByComparingTo("33.3");
    }
}
