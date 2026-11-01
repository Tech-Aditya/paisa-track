package com.paisatrack.modules.currency.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.modules.currency.CurrencyService;
import com.paisatrack.modules.currency.dto.CurrencyDtos.AddRateRequest;
import com.paisatrack.modules.currency.dto.CurrencyDtos.ConversionResponse;
import com.paisatrack.modules.currency.dto.CurrencyDtos.CurrencyResponse;
import com.paisatrack.modules.currency.dto.CurrencyDtos.RateResponse;
import com.paisatrack.modules.currency.dto.CurrencyDtos.RefreshResponse;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.security.CurrentUser;
import com.paisatrack.platform.validation.CurrencyCode;

@RestController
@Tag(name = "Currencies", description = "Supported currencies, exchange rates and conversion")
class CurrencyController {

    private final CurrencyService service;
    private final CurrentUser currentUser;

    CurrencyController(CurrencyService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/currencies")
    @Operation(summary = "Supported ISO 4217 currencies with symbol and decimal places")
    ApiResponse<List<CurrencyResponse>> currencies() {
        return ApiResponse.ok(service.currencies());
    }

    @GetMapping("/api/v1/exchange-rates")
    @Operation(summary = "Your rates and provider rates, newest first (up to 200)")
    ApiResponse<List<RateResponse>> rates(@RequestParam(required = false) @CurrencyCode String currency) {
        return ApiResponse.ok(service.rates(currentUser.id(), currency));
    }

    @PostMapping("/api/v1/exchange-rates")
    @Operation(summary = "Add your own rate for a pair and day (replaces one for the same pair and day)")
    ResponseEntity<ApiResponse<RateResponse>> addRate(@Valid @RequestBody AddRateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Rate saved", service.addRate(currentUser.id(), request)));
    }

    @DeleteMapping("/api/v1/exchange-rates/{id}")
    ResponseEntity<Void> deleteRate(@PathVariable UUID id) {
        service.deleteRate(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/exchange-rates/convert")
    @Operation(summary = "Convert an amount with the latest rate on or before a date",
            description = "Returns available=false with a message (not an error) when no rate exists.")
    ApiResponse<ConversionResponse> convert(@RequestParam BigDecimal amount, @RequestParam @CurrencyCode String from,
                                            @RequestParam(required = false) @CurrencyCode String to,
                                            @RequestParam(required = false) LocalDate date) {
        return ApiResponse.ok(service.convert(currentUser.id(), amount, from, to, date));
    }

    @GetMapping("/api/v1/exchange-rates/provider")
    @Operation(summary = "Whether an online rate provider is configured")
    ApiResponse<java.util.Map<String, Boolean>> provider() {
        return ApiResponse.ok(java.util.Map.of("enabled", service.providerEnabled()));
    }

    @PostMapping("/api/v1/exchange-rates/refresh")
    @Operation(summary = "Fetch the provider's latest rates now (when a provider is configured)")
    ApiResponse<RefreshResponse> refresh() {
        return ApiResponse.ok(service.refresh());
    }
}
