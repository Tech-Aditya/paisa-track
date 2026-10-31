package com.paisatrack.income;

import java.time.YearMonth;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.common.api.ApiResponse;
import com.paisatrack.income.dto.CreateIncomeSourceRequest;
import com.paisatrack.income.dto.ExpectedIncomeResponse;
import com.paisatrack.income.dto.IncomeSourceResponse;
import com.paisatrack.income.dto.SetExpectationRequest;
import com.paisatrack.income.dto.UpdateIncomeSourceRequest;
import com.paisatrack.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/income-sources")
@Tag(name = "Income sources", description = "Salary and other sources, expected vs received. Record receipts via POST /income with incomeSourceId")
class IncomeSourceController {

    private final IncomeSourceService service;
    private final CurrentUser currentUser;

    IncomeSourceController(IncomeSourceService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "List my income sources with this month's status")
    ApiResponse<List<IncomeSourceResponse>> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(service.list(currentUser.id(), includeInactive));
    }

    @GetMapping("/expected")
    @Operation(summary = "Expected vs received for one month", description = "month=YYYY-MM, defaults to this month.")
    ApiResponse<List<ExpectedIncomeResponse>> expected(@RequestParam(required = false) YearMonth month) {
        return ApiResponse.ok(service.monthOverview(currentUser.id(), month));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one income source")
    ApiResponse<IncomeSourceResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(currentUser.id(), id));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Month-by-month expected vs received for a year (default this year)")
    ApiResponse<List<ExpectedIncomeResponse>> history(@PathVariable UUID id, @RequestParam(required = false) Integer year) {
        return ApiResponse.ok(service.history(currentUser.id(), id, year));
    }

    @PostMapping
    @Operation(summary = "Add an income source, e.g. monthly salary")
    ResponseEntity<ApiResponse<IncomeSourceResponse>> create(@Valid @RequestBody CreateIncomeSourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Income source created", service.create(currentUser.id(), request)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update name, type, category, account or notes")
    ApiResponse<IncomeSourceResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateIncomeSourceRequest request) {
        return ApiResponse.ok("Income source updated", service.update(currentUser.id(), id, request));
    }

    @PutMapping("/{id}/expectation")
    @Operation(summary = "Change the expected monthly amount from a month onward",
            description = "Earlier months keep their old expectation. amount=null stops expecting it.")
    ApiResponse<IncomeSourceResponse> setExpectation(@PathVariable UUID id, @Valid @RequestBody SetExpectationRequest request) {
        return ApiResponse.ok("Expectation updated", service.setExpectation(currentUser.id(), id, request));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Stop tracking this source; recorded income is kept")
    ApiResponse<IncomeSourceResponse> deactivate(@PathVariable UUID id) {
        return ApiResponse.ok("Income source deactivated", service.setActive(currentUser.id(), id, false));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Resume tracking this source")
    ApiResponse<IncomeSourceResponse> activate(@PathVariable UUID id) {
        return ApiResponse.ok("Income source activated", service.setActive(currentUser.id(), id, true));
    }
}
