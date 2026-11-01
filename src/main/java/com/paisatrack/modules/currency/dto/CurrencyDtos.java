package com.paisatrack.modules.currency.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import com.paisatrack.platform.validation.CurrencyCode;

/** Request and response records for currencies and exchange rates. */
public final class CurrencyDtos {

    private CurrencyDtos() {
    }

    public record CurrencyResponse(String code, String name, String symbol,
                                   @Schema(description = "Decimal places used for amounts") int fractionDigits) {
    }

    public record RateResponse(
            UUID id,
            String base,
            String quote,
            @Schema(description = "How many 'quote' one 'base' buys") BigDecimal rate,
            LocalDate date,
            @Schema(description = "MANUAL, or the provider it came from") String source,
            @Schema(description = "Your own rate (can be deleted); provider rates are shared") boolean manual) {
    }

    public record AddRateRequest(
            @NotNull @CurrencyCode @Schema(example = "USD") String base,
            @NotNull @CurrencyCode @Schema(example = "INR") String quote,
            @NotNull @Schema(description = "How many 'quote' one 'base' buys", example = "83.25") BigDecimal rate,
            @Schema(description = "Defaults to today") LocalDate date) {
    }

    /** One conversion, with everything needed to check it later. */
    public record ConversionResponse(
            BigDecimal originalAmount,
            String originalCurrency,
            @Schema(description = "Null when no rate is available") BigDecimal rate,
            BigDecimal convertedAmount,
            String reportingCurrency,
            @Schema(description = "Day of the rate used (on or before the requested date)") LocalDate rateDate,
            String source,
            boolean available,
            String message) {
    }

    public record RefreshResponse(String provider, LocalDate date, int stored) {
    }
}
