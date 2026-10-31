package com.paisatrack.modules.loan.web;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.modules.loan.LoanService;
import com.paisatrack.modules.loan.dto.CreateLoanRequest;
import com.paisatrack.modules.loan.dto.EmiPaymentRequest;
import com.paisatrack.modules.loan.dto.InstallmentResponse;
import com.paisatrack.modules.loan.dto.LoanPreviewRequest;
import com.paisatrack.modules.loan.dto.LoanPreviewResponse;
import com.paisatrack.modules.loan.dto.LoanResponse;
import com.paisatrack.modules.loan.dto.LoanSummaryResponse;
import com.paisatrack.modules.loan.dto.PrepaymentRequest;
import com.paisatrack.modules.loan.dto.UpdateLoanRequest;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.audit.AuditEventResponse;
import com.paisatrack.platform.idempotency.IdempotencyService;
import com.paisatrack.platform.security.CurrentUser;

@RestController
@Tag(name = "Loans & EMIs", description = "Home, vehicle, personal and other loans with EMI schedules")
class LoanController {

    private final LoanService service;
    private final CurrentUser currentUser;

    LoanController(LoanService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/v1/loans/preview")
    @Operation(summary = "EMI calculator: EMI, total interest and schedule, nothing saved")
    ApiResponse<LoanPreviewResponse> preview(@Valid @RequestBody LoanPreviewRequest request) {
        return ApiResponse.ok(service.preview(currentUser.id(), request));
    }

    @GetMapping("/api/v1/loans")
    @Operation(summary = "My loans with outstanding principal and next EMI")
    ApiResponse<List<LoanResponse>> list(@RequestParam(defaultValue = "false") boolean includeClosed) {
        return ApiResponse.ok(service.list(currentUser.id(), includeClosed));
    }

    @GetMapping("/api/v1/loans/summary")
    @Operation(summary = "Outstanding, monthly EMI and interest paid per currency, plus EMIs missed or due soon")
    ApiResponse<LoanSummaryResponse> summary(@RequestParam(defaultValue = "30") @Min(0) @Max(365) int days) {
        return ApiResponse.ok(service.summary(currentUser.id(), days));
    }

    @GetMapping("/api/v1/emis/upcoming")
    @Operation(summary = "EMIs missed or due within N days (default 30), across all loans")
    ApiResponse<List<LoanSummaryResponse.UpcomingEmi>> upcoming(@RequestParam(defaultValue = "30") @Min(0) @Max(365) int days) {
        return ApiResponse.ok(service.upcoming(currentUser.id(), days));
    }

    @GetMapping("/api/v1/loans/{id}")
    @Operation(summary = "One loan with its payment history")
    ApiResponse<LoanResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(currentUser.id(), id));
    }

    @GetMapping("/api/v1/loans/{id}/schedule")
    @Operation(summary = "EMI schedule with principal/interest split and status (PAID, MISSED, DUE, UPCOMING)")
    ApiResponse<List<InstallmentResponse>> schedule(@PathVariable UUID id) {
        return ApiResponse.ok(service.schedule(currentUser.id(), id));
    }

    @GetMapping("/api/v1/loans/{id}/history")
    @Operation(summary = "Audit trail of the loan")
    ApiResponse<List<AuditEventResponse>> history(@PathVariable UUID id) {
        return ApiResponse.ok(service.history(currentUser.id(), id));
    }

    @PostMapping("/api/v1/loans")
    @Operation(summary = "Add a loan", description = "EMI is calculated when omitted. A disbursement account receives the principal (never income).")
    ResponseEntity<ApiResponse<LoanResponse>> create(
            @Valid @RequestBody CreateLoanRequest request,
            @Parameter(description = "Send a unique value to make retries safe")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Loan added", service.create(currentUser.id(), request, idempotencyKey)));
    }

    @PatchMapping("/api/v1/loans/{id}")
    @Operation(summary = "Edit a loan", description = "Terms (amount, rate, tenure, EMI, first EMI date) only before any payment.")
    ApiResponse<LoanResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateLoanRequest request) {
        return ApiResponse.ok("Loan updated", service.update(currentUser.id(), id, request));
    }

    @DeleteMapping("/api/v1/loans/{id}")
    @Operation(summary = "Delete a loan with no payments")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/loans/{id}/emi-payments")
    @Operation(summary = "Pay the next EMI", description = "Interest is recorded as spending, principal only reduces the loan.")
    ResponseEntity<ApiResponse<LoanResponse>> payEmi(
            @PathVariable UUID id, @Valid @RequestBody(required = false) EmiPaymentRequest request,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        EmiPaymentRequest body = request != null ? request : new EmiPaymentRequest(null, null, null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("EMI paid", service.payEmi(currentUser.id(), id, body, idempotencyKey)));
    }

    @PostMapping("/api/v1/loans/{id}/prepayments")
    @Operation(summary = "Prepay principal", description = "EMI stays the same; the remaining schedule is recalculated and ends sooner.")
    ResponseEntity<ApiResponse<LoanResponse>> prepay(
            @PathVariable UUID id, @Valid @RequestBody PrepaymentRequest request,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Prepayment recorded", service.prepay(currentUser.id(), id, request, idempotencyKey)));
    }

    @PostMapping("/api/v1/loans/{id}/payments/{paymentId}/void")
    @Operation(summary = "Void the most recent payment", description = "Its ledger entries are voided too.")
    ApiResponse<LoanResponse> voidPayment(@PathVariable UUID id, @PathVariable UUID paymentId) {
        return ApiResponse.ok("Payment voided", service.voidPayment(currentUser.id(), id, paymentId));
    }
}
