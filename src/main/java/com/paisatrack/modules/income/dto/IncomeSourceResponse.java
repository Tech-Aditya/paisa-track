package com.paisatrack.modules.income.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.modules.income.IncomeSourceType;

public record IncomeSourceResponse(
        UUID id,
        String name,
        IncomeSourceType type,
        String currency,
        Ref category,
        @Schema(description = "Null means your default account") Ref account,
        @Schema(type = "string", example = "2026-01") YearMonth startMonth,
        String notes,
        boolean active,
        @Schema(description = "Expectation in effect this month; null for irregular income") Expectation currentExpectation,
        @Schema(description = "This month's expected vs received") ExpectedIncomeResponse thisMonth,
        Instant createdAt,
        Instant updatedAt) {

    public record Ref(UUID id, String name) {
    }

    public record Expectation(BigDecimal amount, Integer expectedDay,
                              @Schema(type = "string", example = "2026-01") YearMonth effectiveFrom) {
    }
}
