package com.paisatrack.modules.category.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.paisatrack.platform.validation.NullOrNotBlank;

/** Partial update. Renaming keeps every existing transaction linked. */
public record UpdateCategoryRequest(
        @NullOrNotBlank @Size(max = 60) String name,
        @Min(0) @Max(10_000) Integer sortOrder) {
}
