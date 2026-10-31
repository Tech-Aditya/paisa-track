package com.paisatrack.budget.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Size;

import com.paisatrack.validation.NullOrNotBlank;

/** Partial update. Scope, category, period and currency are fixed; create a new budget to change them. */
public record UpdateBudgetRequest(
        @NullOrNotBlank @Size(max = 60) String name,
        BigDecimal amount,
        LocalDate endDate,
        @Size(min = 1, max = 5) List<Integer> alertThresholds) {
}
