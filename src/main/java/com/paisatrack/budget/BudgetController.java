package com.paisatrack.budget;

import java.time.LocalDate;
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

import com.paisatrack.budget.dto.BudgetResponse;
import com.paisatrack.budget.dto.CreateBudgetRequest;
import com.paisatrack.budget.dto.UpdateBudgetRequest;
import com.paisatrack.common.api.ApiResponse;
import com.paisatrack.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/budgets")
@Tag(name = "Budgets", description = "Spending limits with utilization and alert thresholds")
class BudgetController {

    private final BudgetService service;
    private final CurrentUser currentUser;

    BudgetController(BudgetService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "My budgets with utilization", description = "For the period containing `date` (default today).")
    ApiResponse<List<BudgetResponse>> list(@RequestParam(required = false) LocalDate date,
                                           @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(service.list(currentUser.id(), date, includeInactive));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One budget with utilization")
    ApiResponse<BudgetResponse> get(@PathVariable UUID id, @RequestParam(required = false) LocalDate date) {
        return ApiResponse.ok(service.get(currentUser.id(), id, date));
    }

    @PostMapping
    @Operation(summary = "Create a budget", description = "One active MONTHLY/WEEKLY budget per scope, category and currency.")
    ResponseEntity<ApiResponse<BudgetResponse>> create(@Valid @RequestBody CreateBudgetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Budget created", service.create(currentUser.id(), request)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change name, amount, end date or alert thresholds")
    ApiResponse<BudgetResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateBudgetRequest request) {
        return ApiResponse.ok("Budget updated", service.update(currentUser.id(), id, request));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Pause a budget")
    ApiResponse<BudgetResponse> deactivate(@PathVariable UUID id) {
        return ApiResponse.ok("Budget deactivated", service.setActive(currentUser.id(), id, false));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Resume a budget")
    ApiResponse<BudgetResponse> activate(@PathVariable UUID id) {
        return ApiResponse.ok("Budget activated", service.setActive(currentUser.id(), id, true));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a budget")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }
}
