package com.paisatrack.category;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.category.dto.CategoryResponse;
import com.paisatrack.category.dto.CreateCategoryRequest;
import com.paisatrack.category.dto.UpdateCategoryRequest;
import com.paisatrack.common.api.ApiResponse;
import com.paisatrack.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Expense and income categories with subcategories")
class CategoryController {

    private final CategoryService categoryService;
    private final CurrentUser currentUser;

    CategoryController(CategoryService categoryService, CurrentUser currentUser) {
        this.categoryService = categoryService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "My categories as a tree")
    ApiResponse<List<CategoryResponse>> tree(@RequestParam(required = false) CategoryType type,
                                             @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(categoryService.tree(currentUser.id(), type, includeInactive));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one category")
    ApiResponse<CategoryResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(categoryService.get(currentUser.id(), id));
    }

    @PostMapping
    @Operation(summary = "Create a category or subcategory")
    ResponseEntity<ApiResponse<CategoryResponse>> create(@Valid @RequestBody CreateCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Category created", categoryService.create(currentUser.id(), request)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Rename or reorder a category")
    ApiResponse<CategoryResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest request) {
        return ApiResponse.ok("Category updated", categoryService.update(currentUser.id(), id, request));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Hide from new transactions (subcategories too); history is kept")
    ApiResponse<CategoryResponse> deactivate(@PathVariable UUID id) {
        return ApiResponse.ok("Category deactivated", categoryService.deactivate(currentUser.id(), id));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Reactivate a category (subcategories too)")
    ApiResponse<CategoryResponse> activate(@PathVariable UUID id) {
        return ApiResponse.ok("Category activated", categoryService.activate(currentUser.id(), id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an unused category", description = "409 CATEGORY_IN_USE if it has transactions or subcategories.")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        categoryService.delete(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }
}
