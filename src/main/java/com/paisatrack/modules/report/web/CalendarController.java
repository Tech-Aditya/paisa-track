package com.paisatrack.modules.report.web;

import java.time.YearMonth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.modules.report.CalendarService;
import com.paisatrack.modules.report.dto.CalendarResponse;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.security.CurrentUser;
import com.paisatrack.platform.validation.CurrencyCode;

@RestController
@Tag(name = "Calendar", description = "Daily income and spending plus bills, EMIs, debt due dates and expected income for a month")
class CalendarController {

    private final CalendarService service;
    private final CurrentUser currentUser;

    CalendarController(CalendarService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/calendar")
    @Operation(summary = "A month at a glance (default: this month)")
    ApiResponse<CalendarResponse> month(@Parameter(example = "2026-11") @RequestParam(required = false) YearMonth month,
                                        @Parameter(description = "Daily totals in this currency; defaults to your preferred one")
                                        @RequestParam(required = false) @CurrencyCode String currency) {
        return ApiResponse.ok(service.month(currentUser.id(), month, currency));
    }
}
