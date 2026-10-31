package com.paisatrack.modules.recurring.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** What is still expected: overdue plus due within the window. Plans only, never in income or spending totals. */
public record RecurringSummaryResponse(int days, List<CurrencyTotals> currencies, List<OccurrenceResponse> expected) {

    public record CurrencyTotals(
            String currency,
            @Schema(description = "Expected expenses and transfers out") BigDecimal expectedOutflow,
            @Schema(description = "Expected income") BigDecimal expectedInflow,
            int overdueCount,
            int dueCount) {
    }
}
