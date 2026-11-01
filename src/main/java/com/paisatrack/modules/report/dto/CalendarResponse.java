package com.paisatrack.modules.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** One month at a glance: what was spent and earned each day, and what is due. */
public record CalendarResponse(
        @Schema(example = "2026-11") String month,
        @Schema(description = "Daily totals are converted into this currency") String currency,
        LocalDate today,
        BigDecimal income,
        BigDecimal expense,
        List<Day> days,
        @Schema(description = "Bills, EMIs, debt due dates and expected income, in their own currency") List<Event> events,
        @Schema(description = "Currencies with entries that couldn't be converted (left out of the totals)") List<String> unconverted) {

    public record Day(LocalDate date, BigDecimal income, BigDecimal expense, long entries) {
    }

    public record Event(
            LocalDate date,
            @Schema(description = "RECURRING, EMI, DEBT_DUE or INCOME_EXPECTED") String kind,
            String title,
            BigDecimal amount,
            String currency,
            @Schema(description = "IN (money coming to you) or OUT") String direction,
            @Schema(description = "UPCOMING, DUE, OVERDUE, DONE or SKIPPED") String status,
            @Schema(description = "The rule, loan, debt or income source it belongs to") UUID refId) {
    }
}
