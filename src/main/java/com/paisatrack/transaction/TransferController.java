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
import com.paisatrack.transaction.dto.TransactionFilter;
import com.paisatrack.transaction.dto.TransactionResponse;
import com.paisatrack.transaction.dto.TransferRequest;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Moves between your own accounts, incl. credit card bill payments. Never income or expense")
class TransferController {

    private final TransactionService transactions;
    private final TransactionQueryService queries;
    private final CurrentUser currentUser;

    TransferController(TransactionService transactions, TransactionQueryService queries, CurrentUser currentUser) {
        this.transactions = transactions;
        this.queries = queries;
        this.currentUser = currentUser;
    }

    @PostMapping
    @Operation(summary = "Record a transfer between two of my accounts")
    ResponseEntity<ApiResponse<TransactionResponse>> create(
            @Valid @RequestBody TransferRequest request,
            @Parameter(description = "Send a unique value to make retries safe")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Transfer recorded",
                transactions.createTransfer(currentUser.id(), request, idempotencyKey)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a transfer")
    ApiResponse<TransactionResponse> update(@PathVariable UUID id, @Valid @RequestBody TransferRequest request) {
        return ApiResponse.ok("Transfer updated", transactions.updateTransfer(currentUser.id(), id, request));
    }

    @GetMapping
    @Operation(summary = "List transfers")
    ApiResponse<PageResponse<TransactionResponse>> list(@Valid @ModelAttribute TransactionFilter filter) {
        return ApiResponse.ok(queries.list(currentUser.id(), filter.withType(TransactionType.TRANSFER)));
    }
}
