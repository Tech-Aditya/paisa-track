package com.paisatrack.modules.account.web;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.modules.account.AccountService;
import com.paisatrack.modules.account.dto.AccountResponse;
import com.paisatrack.modules.account.dto.AccountsSummaryResponse;
import com.paisatrack.modules.account.dto.CreateAccountRequest;
import com.paisatrack.modules.account.dto.UpdateAccountRequest;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Cash, bank, card and wallet accounts with derived balances")
class AccountController {

    private final AccountService accountService;
    private final CurrentUser currentUser;

    AccountController(AccountService accountService, CurrentUser currentUser) {
        this.accountService = accountService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "List my accounts with current balances")
    ApiResponse<List<AccountResponse>> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(accountService.list(currentUser.id(), includeInactive));
    }

    @GetMapping("/summary")
    @Operation(summary = "Assets, liabilities and net worth per currency")
    ApiResponse<AccountsSummaryResponse> summary() {
        return ApiResponse.ok(accountService.summary(currentUser.id()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one account")
    ApiResponse<AccountResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(accountService.get(currentUser.id(), id));
    }

    @PostMapping
    @Operation(summary = "Create an account", description = "The first account you create becomes the default if you have none.")
    ResponseEntity<ApiResponse<AccountResponse>> create(@Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Account created", accountService.create(currentUser.id(), request)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update account details (only supplied fields change)")
    ApiResponse<AccountResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateAccountRequest request) {
        return ApiResponse.ok("Account updated", accountService.update(currentUser.id(), id, request));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate: keeps history, blocks new transactions")
    ApiResponse<AccountResponse> deactivate(@PathVariable UUID id) {
        return ApiResponse.ok("Account deactivated", accountService.deactivate(currentUser.id(), id));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Reactivate a deactivated account")
    ApiResponse<AccountResponse> activate(@PathVariable UUID id) {
        return ApiResponse.ok("Account activated", accountService.activate(currentUser.id(), id));
    }

    @PostMapping("/{id}/make-default")
    @Operation(summary = "Use this account when an expense or income names none")
    ApiResponse<AccountResponse> makeDefault(@PathVariable UUID id) {
        return ApiResponse.ok("Default account updated", accountService.makeDefault(currentUser.id(), id));
    }
}
