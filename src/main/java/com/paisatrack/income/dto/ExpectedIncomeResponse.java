package com.paisatrack.income.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** One month of a source: what was expected versus what was actually recorded for that pay period. */
public record ExpectedIncomeResponse(
        UUID sourceId,
        String sourceName,
        @Schema(type = "string", example = "2026-10") YearMonth month,
        String currency,
        @Schema(description = "Null when nothing was expected that month") BigDecimal expectedAmount,
        LocalDate expectedDate,
        BigDecimal receivedAmount,
        @Schema(description = "Expected minus received, never below zero") BigDecimal remainingAmount,
        Status status) {

    public enum Status {
        /** Received at least the expected amount. */
        RECEIVED,
        PARTIALLY_RECEIVED,
        /** Nothing yet, expected date not passed. */
        PENDING,
        /** Nothing received and the expected date has passed. */
        OVERDUE,
        /** No expectation for the month (received amount may still be non-zero). */
        NOT_EXPECTED
    }
}
