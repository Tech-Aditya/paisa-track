package com.paisatrack.modules.recurring.web;

import java.time.LocalDate;
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

import com.paisatrack.modules.recurring.RecurringService;
import com.paisatrack.modules.recurring.dto.ConfirmOccurrenceRequest;
import com.paisatrack.modules.recurring.dto.CreateRecurringRequest;
import com.paisatrack.modules.recurring.dto.OccurrenceResponse;
import com.paisatrack.modules.recurring.dto.RecurringRuleResponse;
import com.paisatrack.modules.recurring.dto.RecurringSummaryResponse;
import com.paisatrack.modules.recurring.dto.SkipOccurrenceRequest;
import com.paisatrack.modules.recurring.dto.UpdateRecurringRequest;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.audit.AuditEventResponse;
import com.paisatrack.platform.idempotency.IdempotencyService;
import com.paisatrack.platform.security.CurrentUser;

@RestController
@Tag(name = "Recurring", description = "Rent, bills, subscriptions and SIPs: expected occurrences you confirm or skip")
class RecurringController {

    private final RecurringService service;
    private final CurrentUser currentUser;

    RecurringController(RecurringService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/recurring-transactions")
    @Operation(summary = "My recurring rules with their next expected occurrence")
    ApiResponse<List<RecurringRuleResponse>> list(@RequestParam(defaultValue = "false") boolean includeStopped) {
        return ApiResponse.ok(service.list(currentUser.id(), includeStopped));
    }

    @GetMapping("/api/v1/recurring-transactions/summary")
    @Operation(summary = "Still expected: overdue plus due within N days, with per-currency totals (plans, not actuals)")
    ApiResponse<RecurringSummaryResponse> summary(@RequestParam(defaultValue = "30") @Min(0) @Max(31) int days) {
        return ApiResponse.ok(service.summary(currentUser.id(), days));
    }

    @PostMapping("/api/v1/recurring-transactions")
    @Operation(summary = "Add a recurring rule", description = "Occurrences are generated from today on. Nothing is recorded until you confirm one.")
    ResponseEntity<ApiResponse<RecurringRuleResponse>> create(
            @Valid @RequestBody CreateRecurringRequest request,
            @Parameter(description = "Send a unique value to make retries safe")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Recurring rule added", service.create(currentUser.id(), request, idempotencyKey)));
    }

    @GetMapping("/api/v1/recurring-transactions/{id}")
    ApiResponse<RecurringRuleResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(currentUser.id(), id));
    }

    @PatchMapping("/api/v1/recurring-transactions/{id}")
    @Operation(summary = "Edit a rule", description = "Amount, account, category and schedule changes apply to occurrences from today on.")
    ApiResponse<RecurringRuleResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateRecurringRequest request) {
        return ApiResponse.ok("Recurring rule updated", service.update(currentUser.id(), id, request));
    }

    @DeleteMapping("/api/v1/recurring-transactions/{id}")
    @Operation(summary = "Delete a rule that was never confirmed (otherwise stop it)")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/recurring-transactions/{id}/pause")
    ApiResponse<RecurringRuleResponse> pause(@PathVariable UUID id) {
        return ApiResponse.ok("Paused", service.pause(currentUser.id(), id));
    }

    @PostMapping("/api/v1/recurring-transactions/{id}/resume")
    @Operation(summary = "Resume from today; dates missed while paused are not back-filled")
    ApiResponse<RecurringRuleResponse> resume(@PathVariable UUID id) {
        return ApiResponse.ok("Resumed", service.resume(currentUser.id(), id));
    }

    @PostMapping("/api/v1/recurring-transactions/{id}/stop")
    @Operation(summary = "End the rule for good; overdue occurrences can still be confirmed or skipped")
    ApiResponse<RecurringRuleResponse> stop(@PathVariable UUID id) {
        return ApiResponse.ok("Stopped", service.stop(currentUser.id(), id));
    }

    @GetMapping("/api/v1/recurring-transactions/{id}/occurrences")
    @Operation(summary = "Every occurrence of the rule, latest first")
    ApiResponse<List<OccurrenceResponse>> occurrences(@PathVariable UUID id) {
        return ApiResponse.ok(service.occurrences(currentUser.id(), id));
    }

    @GetMapping("/api/v1/recurring-transactions/{id}/history")
    @Operation(summary = "Audit trail of the rule, including confirmations and skips")
    ApiResponse<List<AuditEventResponse>> history(@PathVariable UUID id) {
        return ApiResponse.ok(service.history(currentUser.id(), id));
    }

    @GetMapping("/api/v1/recurring-occurrences")
    @Operation(summary = "Occurrences of all rules in a date range (default: a month either side of today)")
    ApiResponse<List<OccurrenceResponse>> occurrencesBetween(
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) OccurrenceResponse.Status status) {
        return ApiResponse.ok(service.occurrencesBetween(currentUser.id(), from, to, status));
    }

    @PostMapping("/api/v1/recurring-occurrences/{id}/confirm")
    @Operation(summary = "Confirm it happened", description = "Records the actual expense, income or transfer. Amount, date and account are optional.")
    ApiResponse<OccurrenceResponse> confirm(
            @PathVariable UUID id, @Valid @RequestBody(required = false) ConfirmOccurrenceRequest request,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        ConfirmOccurrenceRequest body = request != null ? request : new ConfirmOccurrenceRequest(null, null, null, null);
        return ApiResponse.ok("Confirmed", service.confirm(currentUser.id(), id, body, idempotencyKey));
    }

    @PostMapping("/api/v1/recurring-occurrences/{id}/skip")
    @Operation(summary = "Skip it: it didn't happen this time")
    ApiResponse<OccurrenceResponse> skip(@PathVariable UUID id, @Valid @RequestBody(required = false) SkipOccurrenceRequest request) {
        return ApiResponse.ok("Skipped", service.skip(currentUser.id(), id, request == null ? null : request.reason()));
    }

    @PostMapping("/api/v1/recurring-occurrences/{id}/reopen")
    @Operation(summary = "Undo a confirm (its transaction is voided) or a skip")
    ApiResponse<OccurrenceResponse> reopen(@PathVariable UUID id) {
        return ApiResponse.ok("Reopened", service.reopen(currentUser.id(), id));
    }
}
