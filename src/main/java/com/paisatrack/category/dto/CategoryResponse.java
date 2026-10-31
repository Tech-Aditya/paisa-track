package com.paisatrack.category.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paisatrack.category.CategoryType;

/** A category; top-level entries carry their subcategories in {@code children}. */
public record CategoryResponse(
        UUID id,
        String name,
        CategoryType type,
        UUID parentId,
        String systemCode,
        int sortOrder,
        boolean active,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<CategoryResponse> children) {
}
