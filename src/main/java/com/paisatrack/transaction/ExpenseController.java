package com.paisatrack.transaction;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.common.api.ApiResponse;
import com.paisatrack.common.api.PageResponse;
import com.paisatrack.idempotency.IdempotencyService;
import com.paisatrack.security.CurrentUser;
import com.paisatrack.transaction.dto.ExpenseRequest;
import com.paisatrack.transaction.dto.TransactionFilter;
import com.paisatrack.transaction.dto.TransactionResponse;

@RestController
@RequestMapping("/api/v1/expenses")
@Tag(name = "Expenses", description = "Day-to-day spending. Void/restore/history live under /transactions")
class ExpenseController {

    private final TransactionService transactions;
    private final TransactionQueryService queries;
    private final CurrentUser currentUser;

    ExpenseController(TransactionService transactions, TransactionQueryService queries, CurrentUser currentUser) {
        this.transactions = transactions;
        this.queries = queries;
        this.currentUser = currentUser;
    }

    @PostMapping
    @Operation(summary = "Record an expense", description = "Only amount and categoryId are required.")
    ResponseEntity<ApiResponse<TransactionResponse>> create(
            @Valid @RequestBody ExpenseRequest request,
            @Parameter(description = "Send a unique value to make retries safe")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Expense recorded",
                transactions.createExpense(currentUser.id(), request, idempotencyKey)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace an expense", description = "Full replacement: send every field you want to keep.")
    ApiResponse<TransactionResponse> update(@PathVariable UUID id, @Valid @RequestBody ExpenseRequest request) {
        return ApiResponse.ok("Expense updated", transactions.updateExpense(currentUser.id(), id, request));
    }

    @GetMapping
    @Operation(summary = "List expenses (filters as /transactions)")
    ApiResponse<PageResponse<TransactionResponse>> list(@Valid @ModelAttribute TransactionFilter filter) {
        return ApiResponse.ok(queries.list(currentUser.id(), filter.withType(TransactionType.EXPENSE)));
    }
}
