package com.paisatrack.modules.debt.web;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.modules.debt.DebtService;
import com.paisatrack.modules.debt.dto.DebtResponse;
import com.paisatrack.modules.debt.dto.DebtSummaryResponse;
import com.paisatrack.modules.debt.dto.ReasonRequest;
import com.paisatrack.modules.debt.dto.RepaymentResponse;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.security.CurrentUser;

@RestController
@Tag(name = "Repayments & debt summary", description = "Repayments across lending and borrowing, totals and reminders")
class RepaymentController {

    private final DebtService service;
    private final CurrentUser currentUser;

    RepaymentController(DebtService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/repayments/{id}")
    @Operation(summary = "One repayment")
    ApiResponse<RepaymentResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.getRepayment(currentUser.id(), id));
    }

    @PostMapping("/api/v1/repayments/{id}/void")
    @Operation(summary = "Void a repayment entered by mistake", description = "Kept in history; outstanding goes back up.")
    ApiResponse<DebtResponse> voidRepayment(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest request) {
        return ApiResponse.ok("Repayment voided", service.voidRepayment(currentUser.id(), id, request == null ? null : request.reason()));
    }

    @GetMapping("/api/v1/debts/summary")
    @Operation(summary = "Owed to you and by you, per currency, with overdue totals")
    ApiResponse<DebtSummaryResponse> summary() {
        return ApiResponse.ok(service.summary(currentUser.id()));
    }

    @GetMapping("/api/v1/debts/reminders")
    @Operation(summary = "Open records overdue or due/reminding within N days (default 14)")
    ApiResponse<List<DebtResponse>> reminders(@RequestParam(defaultValue = "14") @Min(0) @Max(365) int days) {
        return ApiResponse.ok(service.reminders(currentUser.id(), days));
    }
}
