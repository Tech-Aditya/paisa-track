package com.paisatrack.modules.report.web;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.modules.report.ReportService;
import com.paisatrack.modules.report.dto.ReportResponse;
import com.paisatrack.modules.transaction.TransactionType;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.security.CurrentUser;
import com.paisatrack.platform.validation.CurrencyCode;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Daily, weekly, monthly, yearly and custom reports with comparisons and insights")
class ReportController {

    private static final String CURRENCY = "Defaults to your preferred currency; other currencies are counted, not added";

    private final ReportService service;
    private final CurrentUser currentUser;

    ReportController(ReportService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/daily")
    @Operation(summary = "One day (default today), compared with the day before")
    ApiResponse<ReportResponse> daily(@RequestParam(required = false) LocalDate date,
                                      @Parameter(description = CURRENCY) @RequestParam(required = false) @CurrencyCode String currency) {
        return ApiResponse.ok(service.daily(currentUser.id(), date, currency));
    }

    @GetMapping("/weekly")
    @Operation(summary = "The week containing a date (default today), starting on your week-start day")
    ApiResponse<ReportResponse> weekly(@RequestParam(required = false) LocalDate date,
                                       @Parameter(description = CURRENCY) @RequestParam(required = false) @CurrencyCode String currency) {
        return ApiResponse.ok(service.weekly(currentUser.id(), date, currency));
    }

    @GetMapping("/monthly")
    @Operation(summary = "A calendar month (default this month), with salary use, EMIs, lending and budgets")
    ApiResponse<ReportResponse> monthly(@Parameter(example = "2026-10") @RequestParam(required = false) YearMonth month,
                                        @Parameter(description = CURRENCY) @RequestParam(required = false) @CurrencyCode String currency) {
        return ApiResponse.ok(service.monthly(currentUser.id(), month, currency));
    }

    @GetMapping("/yearly")
    @Operation(summary = "A calendar year (default this year), month by month")
    ApiResponse<ReportResponse> yearly(@RequestParam(required = false) @Min(2000) @Max(2100) Integer year,
                                       @Parameter(description = CURRENCY) @RequestParam(required = false) @CurrencyCode String currency) {
        return ApiResponse.ok(service.yearly(currentUser.id(), year, currency));
    }

    @GetMapping("/custom")
    @Operation(summary = "Any range up to two years, optionally for one account, category (with subcategories) or type")
    ApiResponse<ReportResponse> custom(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                       @RequestParam(required = false) UUID accountId,
                                       @RequestParam(required = false) UUID categoryId,
                                       @RequestParam(required = false) TransactionType type,
                                       @Parameter(description = CURRENCY) @RequestParam(required = false) @CurrencyCode String currency) {
        return ApiResponse.ok(service.custom(currentUser.id(), from, to, accountId, categoryId, type, currency));
    }
}
