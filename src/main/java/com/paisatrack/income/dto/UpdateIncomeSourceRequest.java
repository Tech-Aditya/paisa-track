package com.paisatrack.income.dto;

import java.util.UUID;

import jakarta.validation.constraints.Size;

import com.paisatrack.income.IncomeSourceType;
import com.paisatrack.validation.NullOrNotBlank;

/** Partial update of details. Expected amounts change through PUT .../expectation so history is kept. */
public record UpdateIncomeSourceRequest(
        @NullOrNotBlank @Size(max = 60) String name,
        IncomeSourceType type,
        UUID categoryId,
        UUID accountId,
        @Size(max = 500) String notes) {
}
