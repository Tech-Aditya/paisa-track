package com.paisatrack.dashboard;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.common.api.ApiResponse;
import com.paisatrack.dashboard.dto.DashboardResponse;
import com.paisatrack.dashboard.dto.DashboardResponse.CategorySpending;
import com.paisatrack.security.CurrentUser;
import com.paisatrack.transaction.dto.TransactionResponse;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Home-screen summaries in your default currency")
class DashboardController {

    private final DashboardService service;
    private final CurrentUser currentUser;

    DashboardController(DashboardService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Today, week, month, balances, budgets, expected income, charts and recent activity",
            description = "Optional from/to (max 366 days) adds a 'selected' period and drives the category and daily charts; default is this month.")
    ApiResponse<DashboardResponse> dashboard(@RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to) {
        return ApiResponse.ok(service.dashboard(currentUser.id(), from, to));
    }

    @GetMapping("/recent-transactions")
    @Operation(summary = "Latest transactions, newest first")
    ApiResponse<List<TransactionResponse>> recent(@RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return ApiResponse.ok(service.recent(currentUser.id(), limit));
    }

    @GetMapping("/category-spending")
    @Operation(summary = "Spending by top-level category with shares (default this month)")
    ApiResponse<List<CategorySpending>> categorySpending(@RequestParam(required = false) LocalDate from,
                                                         @RequestParam(required = false) LocalDate to) {
        return ApiResponse.ok(service.categorySpending(currentUser.id(), from, to));
    }
}
