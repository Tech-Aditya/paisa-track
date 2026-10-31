package com.paisatrack.modules.income.dto;

import java.util.UUID;

import jakarta.validation.constraints.Size;

import com.paisatrack.modules.income.IncomeSourceType;
import com.paisatrack.platform.validation.NullOrNotBlank;

/** Partial update of details. Expected amounts change through PUT .../expectation so history is kept. */
public record UpdateIncomeSourceRequest(
        @NullOrNotBlank @Size(max = 60) String name,
        IncomeSourceType type,
        UUID categoryId,
        UUID accountId,
        @Size(max = 500) String notes) {
}
