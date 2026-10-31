package com.paisatrack.modules.debt.web;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
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

import com.paisatrack.modules.debt.DebtDirection;
import com.paisatrack.modules.debt.DebtService;
import com.paisatrack.modules.debt.DebtStatus;
import com.paisatrack.modules.debt.dto.CreateDebtRequest;
import com.paisatrack.modules.debt.dto.DebtResponse;
import com.paisatrack.modules.debt.dto.ReasonRequest;
import com.paisatrack.modules.debt.dto.RepaymentRequest;
import com.paisatrack.modules.debt.dto.UpdateDebtRequest;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.audit.AuditEventResponse;
import com.paisatrack.platform.idempotency.IdempotencyService;
import com.paisatrack.platform.security.CurrentUser;

/** Shared endpoints for /lending and /borrowing; subclasses only fix the direction. */
abstract class DebtController {

    private final DebtService service;
    private final CurrentUser currentUser;
    private final DebtDirection direction;

    DebtController(DebtService service, CurrentUser currentUser, DebtDirection direction) {
        this.service = service;
        this.currentUser = currentUser;
        this.direction = direction;
    }

    @GetMapping
    @Operation(summary = "List records with outstanding amounts and status")
    ApiResponse<List<DebtResponse>> list(@RequestParam(required = false) DebtStatus status,
                                         @RequestParam(required = false) UUID personId) {
        return ApiResponse.ok(service.list(currentUser.id(), direction, status, personId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One record with its full repayment history")
    ApiResponse<DebtResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(currentUser.id(), direction, id));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Audit trail: creation, edits, repayments, voids, write-offs")
    ApiResponse<List<AuditEventResponse>> history(@PathVariable UUID id) {
        return ApiResponse.ok(service.history(currentUser.id(), direction, id));
    }

    @PostMapping
    @Operation(summary = "Record money lent or borrowed", description = "Tracking only: account balances, income and spending are not affected.")
    ResponseEntity<ApiResponse<DebtResponse>> create(
            @Valid @RequestBody CreateDebtRequest request,
            @Parameter(description = "Send a unique value to make retries safe")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Record created", service.create(currentUser.id(), direction, request, idempotencyKey)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Edit a record (only supplied fields change)")
    ApiResponse<DebtResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateDebtRequest request) {
        return ApiResponse.ok("Record updated", service.update(currentUser.id(), direction, id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a record entered by mistake", description = "Only when it has no active repayments.")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(currentUser.id(), direction, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/repayments")
    @Operation(summary = "Record a full or partial repayment", description = "422 REPAYMENT_EXCEEDS_OUTSTANDING if more than is owed.")
    ResponseEntity<ApiResponse<DebtResponse>> repay(
            @PathVariable UUID id, @Valid @RequestBody RepaymentRequest request,
            @Parameter(description = "Send a unique value to make retries safe")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Repayment recorded", service.repay(currentUser.id(), direction, id, request, idempotencyKey)));
    }

    @PostMapping("/{id}/write-off")
    @Operation(summary = "Close with the remaining balance unpaid", description = "Not counted as income or expense.")
    ApiResponse<DebtResponse> writeOff(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest request) {
        return ApiResponse.ok("Written off", service.writeOff(currentUser.id(), direction, id, request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/reopen")
    @Operation(summary = "Undo a write-off")
    ApiResponse<DebtResponse> reopen(@PathVariable UUID id) {
        return ApiResponse.ok("Reopened", service.reopen(currentUser.id(), direction, id));
    }
}
