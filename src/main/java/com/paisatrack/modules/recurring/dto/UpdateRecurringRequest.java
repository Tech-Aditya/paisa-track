package com.paisatrack.modules.recurring.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.recurring.Frequency;
import com.paisatrack.modules.recurring.RecurringKind;
import com.paisatrack.modules.transaction.PaymentMethod;
import com.paisatrack.platform.validation.NullOrNotBlank;

/** Only the fields present change. Amount and schedule changes apply to occurrences from today on. */
public record UpdateRecurringRequest(
        @NullOrNotBlank @Size(max = 80) String name,
        RecurringKind kind,
        BigDecimal amount,
        UUID accountId,
        UUID toAccountId,
        UUID categoryId,
        PaymentMethod paymentMethod,
        @Size(max = 100) String counterparty,
        @Size(max = 200) String description,
        @Size(max = 1000) String notes,
        Frequency frequency,
        @Min(1) @Max(99) Integer interval,
        LocalDate startDate,
        LocalDate endDate,
        @Schema(description = "true removes the end date") Boolean noEndDate) {
}
