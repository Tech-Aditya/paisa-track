package com.paisatrack.modules.budget.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.paisatrack.modules.budget.BudgetPeriod;
import com.paisatrack.modules.budget.BudgetScope;

public record BudgetResponse(
        UUID id,
        String name,
        BudgetScope scope,
        CategoryInfo category,
        BudgetPeriod period,
        BigDecimal amount,
        String currency,
        LocalDate startDate,
        LocalDate endDate,
        List<Integer> alertThresholds,
        boolean active,
        @Schema(description = "Utilization for the period containing the requested date") Utilization utilization,
        Instant createdAt,
        Instant updatedAt) {

    public record CategoryInfo(UUID id, String name) {
    }

    public record Utilization(
            @Schema(description = "Null when the budget isn't in effect on the date") LocalDate periodStart,
            LocalDate periodEnd,
            @Schema(description = "Actual expenses only; transfers never count") BigDecimal spent,
            @Schema(description = "Negative when overspent") BigDecimal remaining,
            BigDecimal percentUsed,
            Status status,
            @Schema(description = "Alert thresholds already reached") List<Integer> crossedThresholds,
            @Schema(description = "Days left in the period, including the date") Long daysLeft,
            @Schema(description = "What can still be spent per remaining day") BigDecimal dailyAllowance) {
    }

    public enum Status {
        /** Below every alert threshold. */
        ON_TRACK,
        /** At or past an alert threshold, not over the limit. */
        ALERT,
        /** Spent more than the budget. */
        EXCEEDED,
        /** The date is outside the budget's start/end dates, or it is deactivated. */
        NOT_IN_EFFECT
    }
}
