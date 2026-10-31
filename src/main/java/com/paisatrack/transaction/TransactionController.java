package com.paisatrack.transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.audit.AuditEventResponse;
import com.paisatrack.common.api.ApiResponse;
import com.paisatrack.common.api.PageResponse;
import com.paisatrack.security.CurrentUser;
import com.paisatrack.transaction.dto.DailySummaryResponse;
import com.paisatrack.transaction.dto.TransactionFilter;
import com.paisatrack.transaction.dto.TransactionResponse;
import com.paisatrack.transaction.dto.VoidRequest;

@RestController
@Tag(name = "Transactions", description = "All money movements: history, void/restore, audit trail, daily summary")
class TransactionController {

    private final TransactionService transactions;
    private final TransactionQueryService queries;
    private final CurrentUser currentUser;

    TransactionController(TransactionService transactions, TransactionQueryService queries, CurrentUser currentUser) {
        this.transactions = transactions;
        this.queries = queries;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/transactions")
    @Operation(summary = "Search my transactions", description = "Newest first by default; voided entries hidden unless includeVoided=true.")
    ApiResponse<PageResponse<TransactionResponse>> list(@Valid @ModelAttribute TransactionFilter filter) {
        return ApiResponse.ok(queries.list(currentUser.id(), filter));
    }

    @GetMapping("/api/v1/transactions/daily-summary")
    @Operation(summary = "Income, spending and category totals for one day",
            description = "Defaults to today in your timezone. Transfers and voided entries are excluded.")
    ApiResponse<DailySummaryResponse> dailySummary(@RequestParam(required = false) LocalDate date) {
        return ApiResponse.ok(queries.dailySummary(currentUser.id(), date));
    }

    @GetMapping("/api/v1/transactions/{id}")
    @Operation(summary = "Get one transaction")
    ApiResponse<TransactionResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(queries.get(currentUser.id(), id));
    }

    @PostMapping("/api/v1/transactions/{id}/void")
    @Operation(summary = "Delete (void) a transaction", description = "Kept for history, removed from balances and totals. Restorable.")
    ApiResponse<TransactionResponse> voidTransaction(@PathVariable UUID id,
                                                     @Valid @RequestBody(required = false) VoidRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok("Transaction voided", transactions.voidTransaction(currentUser.id(), id, reason));
    }

    @PostMapping("/api/v1/transactions/{id}/restore")
    @Operation(summary = "Restore a voided transaction")
    ApiResponse<TransactionResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok("Transaction restored", transactions.restore(currentUser.id(), id));
    }

    @GetMapping("/api/v1/transactions/{id}/history")
    @Operation(summary = "Audit trail: creation, every edit (from/to), voids and restores")
    ApiResponse<List<AuditEventResponse>> history(@PathVariable UUID id) {
        return ApiResponse.ok(queries.history(currentUser.id(), id));
    }

    @GetMapping("/api/v1/accounts/{accountId}/transactions")
    @Operation(summary = "Transaction history of one account (both sides of transfers)")
    ApiResponse<PageResponse<TransactionResponse>> accountHistory(@PathVariable UUID accountId,
                                                                   @Valid @ModelAttribute TransactionFilter filter) {
        return ApiResponse.ok(queries.listForAccount(currentUser.id(), accountId, filter));
    }
}
