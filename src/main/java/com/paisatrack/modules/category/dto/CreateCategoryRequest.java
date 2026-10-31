package com.paisatrack.modules.category.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.paisatrack.modules.category.CategoryType;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 60) String name,
        @Schema(description = "Required for top-level categories; subcategories inherit the parent's type") CategoryType type,
        @Schema(description = "Set to create a subcategory under a top-level category") UUID parentId) {
}
