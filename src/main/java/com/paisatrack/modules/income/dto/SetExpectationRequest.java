package com.paisatrack.modules.income.dto;

import java.math.BigDecimal;
import java.time.YearMonth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** New expected amount from a month onward. Earlier months keep their old expectation. */
public record SetExpectationRequest(
        @Schema(description = "Expected monthly amount; null stops expecting it from effectiveFrom") BigDecimal amount,
        @Schema(description = "Expected credit day (1-31); defaults to the current one, else 1") @Min(1) @Max(31) Integer expectedDay,
        @Schema(description = "First month the change applies to; defaults to this month", type = "string", example = "2026-11")
        YearMonth effectiveFrom) {
}
